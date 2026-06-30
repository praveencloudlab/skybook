# ✈️ SkyBook Flight Scheduling Module

---

## Project Information

| Property | Value |
|----------|-------|
| Project | SkyBook Airline Reservation System |
| Module | Flight Service — Flight Scheduling (Phase 2) |
| Version | 1.0.0 |
| Status | Implemented (manual + scheduled generation) |
| Java | 21 |
| Spring Boot | 3.5.x |
| Database | PostgreSQL |
| Build Tool | Maven |
| Author | Praveen Somireddy |

---

# Table of Contents

1. Overview
2. What Was Added / Changed
3. Database Changes
4. Domain Model
5. DTOs and Validation Rules
6. API Endpoints
7. Flight Generation Algorithm
8. Scheduled Job
9. Error Handling
10. Files Added / Modified
11. Manual Testing Guide
12. Known Limitations / Follow-Ups

---

# 1. Overview

The Flight Scheduling module adds recurring flight templates to `flight-service`. Previously, every `Flight` row had to be created one at a time via `POST /api/flights`. A real airline doesn't do that — a flight number like `BA178` flies the same route at the same time of day on a fixed set of weekdays for months at a stretch.

`FlightSchedule` is the new entity that represents that recurring pattern. Concrete `Flight` rows (the ones your existing search/booking/check-in flows already use) are generated *from* a schedule on a rolling basis, either manually via an API call or automatically once a day via a scheduled job. This was previously a stub in `FlightService` (`createSchedule()`, `generateFlights()`, etc. all threw `UnsupportedOperationException`) — those stubs have been removed and replaced with a dedicated `FlightScheduleService`.

---

# 2. What Was Added / Changed

New capability:

- Create a recurring flight schedule (route, time-of-day, operating days, validity window).
- Generate `Flight` instances from a schedule, idempotently, on demand or via a daily cron job.
- Pause / resume a schedule (stops/resumes future generation without touching already-generated flights).
- Cancel a schedule (also cancels its not-yet-departed generated flights).
- Extend a schedule's validity window.

Alongside this, two pre-existing gaps were fixed because the scheduling feature exposed them:

- `Flight.flightNumber` was globally unique, which is incompatible with recurring flights (the same flight number legitimately repeats across dates). Uniqueness was moved to the pair `(flightNumber, departureTime)`.
- `GlobalExceptionHandler` only handled `FlightNotFoundException`. It now also handles validation errors, `IllegalArgumentException`, `IllegalStateException`, and the new `FlightScheduleNotFoundException`, returning the documented error shape instead of Spring's default error body.

---

# 3. Database Changes

All tables are created automatically via Hibernate `ddl-auto: update` against `skybook_flight` — no Flyway migration was added (consistent with this service's current setup). Two new tables, one modified table.

## 3.1 New table: `flight_schedules`

| Column | Type | Nullable | Notes |
|---|---|---|---|
| id | BIGINT (identity) | No | Primary key |
| flight_number | VARCHAR(10) | No | Recurring flight number, e.g. `BA178` |
| airline_code | VARCHAR(5) | No | |
| origin_airport_code | VARCHAR(3) | No | |
| destination_airport_code | VARCHAR(3) | No | |
| departure_time | TIME | No | Time-of-day only (`LocalTime`), not a date |
| arrival_time | TIME | No | Time-of-day only. If earlier than departure_time, treated as next-day arrival when generating flights |
| valid_from | DATE | No | First date the schedule can generate flights |
| valid_to | DATE | Yes | Last date; `NULL` = runs indefinitely until paused/cancelled |
| status | VARCHAR | No | `ACTIVE`, `PAUSED`, `CANCELLED`, `COMPLETED` |
| last_generated_date | DATE | Yes | High-water mark — flights have been generated up to and including this date |
| created_at | TIMESTAMP | No | |
| updated_at | TIMESTAMP | No | |

## 3.2 New table: `flight_schedule_operating_days`

Element-collection join table backing `FlightSchedule.operatingDays` (`Set<DayOfWeek>`).

| Column | Type | Notes |
|---|---|---|
| schedule_id | BIGINT | FK to `flight_schedules.id` |
| day_of_week | VARCHAR | One of `MONDAY`..`SUNDAY` |

One row per operating day per schedule (e.g. a Mon/Wed/Fri schedule has 3 rows).

## 3.3 Modified table: `flights`

| Change | Detail |
|---|---|
| New column | `schedule_id BIGINT NULL` — set when a `Flight` was generated from a `FlightSchedule`; `NULL` for flights created manually through `/api/flights`. No DB-level foreign key constraint is enforced (kept as a plain reference, consistent with the rest of the schema). |
| Constraint removed | Single-column `UNIQUE` on `flight_number` |
| Constraint added | Composite `UNIQUE (flight_number, departure_time)`, named `uk_flight_number_departure_time` |

### ⚠️ Manual step likely required

`ddl-auto: update` adds new columns and tables but does **not** reliably drop constraints that already exist from a previous run. If your `flights` table was created before this change, the old single-column unique constraint on `flight_number` is probably still sitting there and will block flight generation the moment the same flight number appears on a second date. Check and fix it manually:

```sql
-- See what's currently on the table
SELECT conname, pg_get_constraintdef(oid)
FROM pg_constraint
WHERE conrelid = 'flights'::regclass AND contype = 'u';

-- If you see a unique constraint on flight_number alone (commonly named
-- flights_flight_number_key), drop it:
ALTER TABLE flights DROP CONSTRAINT flights_flight_number_key;

-- Confirm the new composite constraint exists (Hibernate should have added
-- it automatically on startup as uk_flight_number_departure_time). If not,
-- add it manually:
ALTER TABLE flights
  ADD CONSTRAINT uk_flight_number_departure_time
  UNIQUE (flight_number, departure_time);
```

---

# 4. Domain Model

## 4.1 `ScheduleStatus` enum

| Value | Meaning |
|---|---|
| `ACTIVE` | Schedule generates flights normally (manually or via the cron job) |
| `PAUSED` | Generation is skipped; already-generated flights are untouched |
| `CANCELLED` | Terminal. All not-yet-departed generated flights are cancelled along with it |
| `COMPLETED` | Terminal-ish. Set automatically once generation reaches `validTo`. Can be reactivated to `ACTIVE` by extending `validTo` |

## 4.2 `FlightSchedule` entity

Package: `com.skybook.praveen.flightservice.entity`

| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| flightNumber | String | max 10 chars |
| airlineCode | String | max 5 chars |
| originAirportCode | String | exactly 3 chars |
| destinationAirportCode | String | exactly 3 chars |
| departureTime | LocalTime | time-of-day |
| arrivalTime | LocalTime | time-of-day; earlier-than-departure means next-day arrival |
| operatingDays | Set\<DayOfWeek\> | which weekdays the schedule runs |
| validFrom | LocalDate | |
| validTo | LocalDate (nullable) | |
| status | ScheduleStatus | defaults to `ACTIVE` on persist |
| lastGeneratedDate | LocalDate (nullable) | advanced by each `generateFlights()` call |
| createdAt / updatedAt | LocalDateTime | set via `@PrePersist` / `@PreUpdate` |

## 4.3 `Flight` entity changes

| Field | Change |
|---|---|
| scheduleId | **New.** `Long`, nullable, `@Column(name = "schedule_id")`. Traces a generated flight back to its schedule. |
| flightNumber | No longer individually unique — see Database Changes above. |

---

# 5. DTOs and Validation Rules

## 5.1 `CreateFlightScheduleRequest`

| Field | Type | Validation |
|---|---|---|
| flightNumber | String | `@NotBlank`, max 10 chars |
| airlineCode | String | `@NotBlank`, max 5 chars |
| originAirportCode | String | `@NotBlank`, exactly 3 chars |
| destinationAirportCode | String | `@NotBlank`, exactly 3 chars |
| departureTime | LocalTime | `@NotNull` |
| arrivalTime | LocalTime | `@NotNull` |
| operatingDays | Set\<DayOfWeek\> | `@NotEmpty` |
| validFrom | LocalDate | `@NotNull`, `@FutureOrPresent` |
| validTo | LocalDate | optional |

Additional service-level checks (return `400`):
- `originAirportCode` must differ from `destinationAirportCode`.
- If `validTo` is present, it must be after `validFrom`.

## 5.2 `ExtendFlightScheduleRequest`

| Field | Type | Validation |
|---|---|---|
| newValidTo | LocalDate | `@NotNull`, `@Future` |

Service-level checks: schedule must not be `CANCELLED` (409); `newValidTo` must be after the current `validTo` if one is set (400).

## 5.3 `FlightScheduleResponse`

```
id, flightNumber, airlineCode, originAirportCode, destinationAirportCode,
departureTime, arrivalTime, operatingDays, validFrom, validTo, status,
lastGeneratedDate, createdAt, updatedAt
```

## 5.4 `FlightResponse` (updated)

Added field: `scheduleId` (Long, nullable) — full shape is now:

```
id, flightNumber, airlineCode, originAirportCode, destinationAirportCode,
departureTime, arrivalTime, status, scheduleId, createdAt, updatedAt
```

---

# 6. API Endpoints

Base path: `/api/flight-schedules`

## 6.1 Create Flight Schedule

`POST /api/flight-schedules`

Creates a new recurring flight schedule template. Returns `201 Created`.

Request body:
```json
{
  "flightNumber": "BA178",
  "airlineCode": "BA",
  "originAirportCode": "LHR",
  "destinationAirportCode": "JFK",
  "departureTime": "10:15:00",
  "arrivalTime": "18:25:00",
  "operatingDays": ["MONDAY", "WEDNESDAY", "FRIDAY"],
  "validFrom": "2026-07-01",
  "validTo": "2026-09-30"
}
```

Response `201`:
```json
{
  "id": 1,
  "flightNumber": "BA178",
  "airlineCode": "BA",
  "originAirportCode": "LHR",
  "destinationAirportCode": "JFK",
  "departureTime": "10:15:00",
  "arrivalTime": "18:25:00",
  "operatingDays": ["MONDAY", "WEDNESDAY", "FRIDAY"],
  "validFrom": "2026-07-01",
  "validTo": "2026-09-30",
  "status": "ACTIVE",
  "lastGeneratedDate": null,
  "createdAt": "2026-06-30T21:42:15.5076406",
  "updatedAt": "2026-06-30T21:42:15.5076406"
}
```

Errors: `400` on validation failure, same-origin/destination, or `validTo` before `validFrom`.

## 6.2 Get Flight Schedule By Id

`GET /api/flight-schedules/{id}`

| Param | In | Type | Notes |
|---|---|---|---|
| id | path | Long | |

`200` → `FlightScheduleResponse`. `404` if not found.

## 6.3 Get All Flight Schedules

`GET /api/flight-schedules`

No parameters. `200` → `List<FlightScheduleResponse>`.

## 6.4 Pause Flight Schedule

`PATCH /api/flight-schedules/{id}/pause`

| Param | In | Type |
|---|---|---|
| id | path | Long |

Stops future generation; already-generated flights are untouched. `200` → updated `FlightScheduleResponse` with `status: "PAUSED"`.

Errors: `404` not found, `409` if schedule is already `CANCELLED`.

## 6.5 Resume Flight Schedule

`PATCH /api/flight-schedules/{id}/resume`

| Param | In | Type |
|---|---|---|
| id | path | Long |

`200` → `status: "ACTIVE"`. Errors: `404`, `409` if schedule is not currently `PAUSED`.

## 6.6 Cancel Flight Schedule

`PATCH /api/flight-schedules/{id}/cancel`

| Param | In | Type |
|---|---|---|
| id | path | Long |

Sets the schedule to `CANCELLED` **and** cancels every `Flight` generated from it that hasn't departed yet (`departureTime` in the future, status `SCHEDULED` or `DELAYED` → `CANCELLED`). `200` → updated `FlightScheduleResponse`. Errors: `404`.

## 6.7 Extend Flight Schedule

`PATCH /api/flight-schedules/{id}/extend`

| Param | In | Type |
|---|---|---|
| id | path | Long |

Request body:
```json
{ "newValidTo": "2026-12-31" }
```

Pushes `validTo` further out. If the schedule had already reached `COMPLETED`, it's reactivated to `ACTIVE`. `200` → updated `FlightScheduleResponse`. Errors: `404`, `409` if `CANCELLED`, `400` if `newValidTo` isn't after the current `validTo`.

## 6.8 Generate Flights

`POST /api/flight-schedules/{id}/generate`

| Param | In | Type | Default | Notes |
|---|---|---|---|---|
| id | path | Long | — | |
| horizonDays | query | int | 30 | How many days forward to generate from wherever generation last left off |

Manually triggers generation (this is the same logic the daily cron job calls). `200` → `List<FlightResponse>` containing **only the newly created flights from this call** (empty list if the schedule isn't `ACTIVE`, or if there was nothing left to generate). Idempotent — safe to call repeatedly.

Example response:
```json
[
  {
    "id": 42,
    "flightNumber": "BA178",
    "airlineCode": "BA",
    "originAirportCode": "LHR",
    "destinationAirportCode": "JFK",
    "departureTime": "2026-07-01T10:15:00",
    "arrivalTime": "2026-07-01T18:25:00",
    "status": "SCHEDULED",
    "scheduleId": 1,
    "createdAt": "2026-06-30T22:10:00.123",
    "updatedAt": "2026-06-30T22:10:00.123"
  }
]
```

---

# 7. Flight Generation Algorithm

Implemented in `FlightScheduleServiceImpl.generateFlights(scheduleId, horizonDays)`:

1. Load the schedule. If its status isn't `ACTIVE`, return an empty list immediately (no-op for `PAUSED`/`CANCELLED`/`COMPLETED`).
2. Compute the generation window:
   - `windowStart` = `lastGeneratedDate + 1 day` if generation has run before, otherwise `validFrom`. Clamped forward to today if it's in the past (a schedule that's been idle for a while doesn't try to backfill departed dates).
   - `windowEnd` = `windowStart + horizonDays`, clamped to `validTo` if set.
   - If `windowEnd` is before `windowStart`, the schedule has fully run its course — status flips to `COMPLETED` and nothing is generated.
3. Walk every calendar date from `windowStart` to `windowEnd`. For each date whose `DayOfWeek` is in `operatingDays`:
   - Compute `departureDateTime = date + departureTime`.
   - Compute `arrivalDateTime`: same date if `arrivalTime` is after `departureTime`, otherwise the **next** date (handles overnight flights).
   - Check `existsByFlightNumberAndDepartureTime(flightNumber, departureDateTime)`. If a flight already exists for that exact slot, skip it — this is what makes the operation idempotent even if called twice over the same window.
   - Otherwise build a new `Flight` (`status = SCHEDULED`, `scheduleId` = this schedule's id) and add it to the batch.
4. Bulk-save all newly built flights.
5. Set `lastGeneratedDate = windowEnd`. If `validTo` has now been reached, flip status to `COMPLETED`.
6. Return the newly created flights, mapped to `FlightResponse`.

`generateFlightsForAllActiveSchedules(horizonDays)` simply loops `findByStatus(ACTIVE)` and calls the above for each — this is what the cron job calls.

---

# 8. Scheduled Job

`FlightGenerationJob` (`com.skybook.praveen.flightservice.scheduler`):

```java
@Scheduled(cron = "0 0 1 * * *")
public void generateUpcomingFlights() {
    flightScheduleService.generateFlightsForAllActiveSchedules(30);
}
```

Runs once a day at **01:00 server time**, rolling every `ACTIVE` schedule's generated window forward by 30 days. `@EnableScheduling` was added to `FlightServiceApplication` to turn this on — no new Maven dependency was needed, `@Scheduled` comes from `spring-context`, already pulled in transitively by `spring-boot-starter-web`.

---

# 9. Error Handling

`GlobalExceptionHandler` now maps exceptions to the standard error shape (`timestamp`, `status`, `error`, `message`, `path`) consistently:

| Exception | HTTP Status |
|---|---|
| `FlightNotFoundException` | 404 |
| `FlightScheduleNotFoundException` (new) | 404 |
| `IllegalStateException` | 409 (invalid lifecycle transition, e.g. resuming a non-paused schedule) |
| `IllegalArgumentException` | 400 (bad input, e.g. same origin/destination, duplicate flight number+time) |
| `MethodArgumentNotValidException` | 400 (bean validation failures, e.g. missing `operatingDays`) |

Previously, only `FlightNotFoundException` was handled — everything else fell through to Spring's default error body. This was tightened up as part of this change since the new schedule lifecycle methods rely on `IllegalStateException`/`IllegalArgumentException` for their error signaling.

---

# 10. Files Added / Modified

**New files:**
```
enums/ScheduleStatus.java
entity/FlightSchedule.java
dto/request/CreateFlightScheduleRequest.java
dto/request/ExtendFlightScheduleRequest.java
dto/response/FlightScheduleResponse.java
repository/FlightScheduleRepository.java
mapper/FlightScheduleMapper.java
service/FlightScheduleService.java
service/impl/FlightScheduleServiceImpl.java
controller/FlightScheduleController.java
exception/FlightScheduleNotFoundException.java
scheduler/FlightGenerationJob.java
```

**Modified files:**
```
entity/Flight.java                  - schedule_id column, unique constraint moved to (flight_number, departure_time)
repository/FlightRepository.java    - existsByFlightNumberAndDepartureTime, findByScheduleIdAndDepartureTimeAfter
service/FlightService.java          - removed unused no-arg schedule stub methods
service/impl/FlightServiceImpl.java - removed stub methods; validateFlightCreation now checks (flightNumber, departureTime)
dto/response/FlightResponse.java    - added scheduleId field
mapper/FlightMapper.java            - maps scheduleId into FlightResponse
exception/GlobalExceptionHandler.java - added handlers (see section 9)
FlightServiceApplication.java       - added @EnableScheduling
```

No changes were required to `pom.xml` — everything used is already pulled in by `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, and `spring-boot-starter-validation`.

---

# 11. Manual Testing Guide

1. **Create a schedule** — `POST /api/flight-schedules` (see 6.1 for a full example).
2. **Generate flights** — `POST /api/flight-schedules/{id}/generate?horizonDays=30`. Check the response and `GET /api/flights` — generated rows should have `scheduleId` set, manually-created rows should have `scheduleId: null`.
3. **Re-run generate** with the same horizon — response should not duplicate flights already created (idempotency check).
4. **Pause** — `PATCH /api/flight-schedules/{id}/pause`, then call `/generate` again — expect `[]`.
5. **Resume** — `PATCH /api/flight-schedules/{id}/resume`, then `/generate` continues from `lastGeneratedDate`.
6. **Extend** — `PATCH /api/flight-schedules/{id}/extend` with `{"newValidTo": "..."}`.
7. **Cancel** — `PATCH /api/flight-schedules/{id}/cancel`, then `GET /api/flights/status/CANCELLED` — not-yet-departed flights tied to that `scheduleId` should appear there.
8. Swagger UI at `/swagger-ui.html` picks up `FlightScheduleController` automatically (`springdoc.packages-to-scan` already points at the controller package) — useful for poking at this interactively instead of curl/Postman.

---

# 12. Known Limitations / Follow-Ups

- No authentication/authorization on these endpoints yet — consistent with the rest of `flight-service` today, but schedule management should ultimately be `ADMIN`-only per the SRS.
- `flight-service`'s datasource must point at `skybook_flight`, not `skybook_auth` (a pre-existing misconfiguration, unrelated to this module but it affects where these new tables land — see prior review notes).
- No DB-level foreign key from `flights.schedule_id` to `flight_schedules.id`, and none from `flight_schedule_operating_days.schedule_id` either beyond what Hibernate's element-collection mapping implies — acceptable for now, worth revisiting if referential integrity becomes a concern.
- `departureTime`/`arrivalTime` are naive `LocalTime`/`LocalDateTime` with no timezone — fine for a single-timezone portfolio project, would need rework for multi-timezone realism.
- Soft delete / restore for schedules isn't implemented — cancelling is the only terminal state short of deleting the row directly.
