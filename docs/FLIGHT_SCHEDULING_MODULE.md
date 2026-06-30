# ✈️ SkyBook Flight Scheduling Module

---

## Project Information

| Property | Value |
|----------|-------|
| Project | SkyBook Airline Reservation System |
| Module | Flight Service — Flight Scheduling (Phase 2) |
| Version | 1.1.0 (post design-review improvements) |
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
12. Deferred / Out of Scope
13. Known Limitations / Follow-Ups

---

# 1. Overview

The Flight Scheduling module adds recurring flight templates to `flight-service`. Previously, every `Flight` row had to be created one at a time via `POST /api/flights`. A real airline doesn't do that — a flight number like `BA178` flies the same route at the same time of day on a fixed set of weekdays for months at a stretch.

`FlightSchedule` is the entity that represents that recurring pattern. Concrete `Flight` rows (the ones your existing search/booking/check-in flows already use) are generated *from* a schedule on a rolling basis, either manually via an API call or automatically once a day via a scheduled job. This was previously a stub in `FlightService` (`createSchedule()`, `generateFlights()`, etc. all threw `UnsupportedOperationException`) — those stubs have been removed and replaced with a dedicated `FlightScheduleService`.

This version reflects a design review pass: the initial implementation used a raw `scheduleId` foreign key on `Flight`, hand-rolled audit timestamps, no human-readable schedule identifier, a single hardcoded generation horizon, and no record of *why* a schedule was paused/cancelled. All five have been addressed (see section 2). Two further suggestions from that review — an `AUTO`/`MANUAL` generation strategy switch, and dedicated `FlightStatusHistory`/`FlightDelayHistory`/`FlightGateHistory` tables — were intentionally deferred; see section 12.

---

# 2. What Was Added / Changed

Core capability (unchanged from the original implementation):

- Create a recurring flight schedule (route, time-of-day, operating days, validity window).
- Generate `Flight` instances from a schedule, idempotently, on demand or via a daily cron job.
- Pause / resume a schedule (stops/resumes future generation without touching already-generated flights).
- Cancel a schedule (also cancels its not-yet-departed generated flights).
- Extend a schedule's validity window.

Design-review improvements applied in this revision:

| # | Change | Why |
|---|---|---|
| 1 | `Flight.scheduleId` (raw `Long`) replaced with `Flight.schedule` — a real `@ManyToOne(fetch = LAZY)` to `FlightSchedule` | Lets code navigate to schedule details directly instead of a second lookup by id; the DB column (`schedule_id`) is unchanged, so this is a code-level change only. `FlightResponse.scheduleId` is still exposed in the API, just derived from `flight.getSchedule().getId()` now. |
| 2 | `createdBy`, `updatedBy`, `version` added to both `Flight` and `FlightSchedule`, via a shared `Auditable` `@MappedSuperclass` and Spring Data JPA auditing | Standard enterprise audit trail + optimistic locking, without duplicating the same five fields across every entity. |
| 3 | `FlightSchedule.scheduleCode` — immutable, system-assigned identifier, e.g. `SCH-LHR-JFK-000001` | Gives schedules their own stable identity independent of the (re-used) airline flight number. |
| 4 | `FlightSchedule.generationDaysAhead` (per-schedule, default 30) replaces the previously hardcoded `30` used by both the manual endpoint and the cron job | Different routes may want different horizons (e.g. a seasonal charter vs. a long-running mainline route); the cron job now generates each schedule using its own configured value. |
| 5 | `statusReason` / `statusRemarks` added to `FlightSchedule`, settable on pause/cancel, cleared on resume | Captures *why* a schedule stopped generating (e.g. "Runway Maintenance", "Weather"), not just that it did. |

---

# 3. Database Changes

All tables/columns are created automatically via Hibernate `ddl-auto: update` against `skybook_flight` — no Flyway migration was added (consistent with this service's current setup).

## 3.1 New table: `flight_schedules`

| Column | Type | Nullable | Notes |
|---|---|---|---|
| id | BIGINT (identity) | No | Primary key |
| schedule_code | VARCHAR(30) | No, **unique**, immutable | System-generated, e.g. `SCH-LHR-JFK-000001` |
| flight_number | VARCHAR(10) | No | Recurring flight number, e.g. `BA178` |
| airline_code | VARCHAR(5) | No | |
| origin_airport_code | VARCHAR(3) | No | |
| destination_airport_code | VARCHAR(3) | No | |
| departure_time | TIME | No | Time-of-day only (`LocalTime`), not a date |
| arrival_time | TIME | No | Time-of-day only. If earlier than departure_time, treated as next-day arrival when generating flights |
| valid_from | DATE | No | First date the schedule can generate flights |
| valid_to | DATE | Yes | Last date; `NULL` = runs indefinitely until paused/cancelled |
| status | VARCHAR | No | `ACTIVE`, `PAUSED`, `CANCELLED`, `COMPLETED` |
| last_generated_date | DATE | Yes | High-water mark — flights generated up to and including this date |
| generation_days_ahead | INTEGER | No | Per-schedule generation horizon, default 30 |
| status_reason | VARCHAR | Yes | Why the schedule is paused/cancelled |
| status_remarks | VARCHAR(500) | Yes | Free-text notes accompanying status_reason |
| created_by / updated_by | VARCHAR(100) | Yes | Auditing — see 4.1 |
| version | BIGINT | Yes | Optimistic locking |
| created_at / updated_at | TIMESTAMP | No | Auditing |

## 3.2 New table: `flight_schedule_operating_days`

Element-collection join table backing `FlightSchedule.operatingDays` (`Set<DayOfWeek>`). Unchanged from the original design.

| Column | Type | Notes |
|---|---|---|
| schedule_id | BIGINT | FK to `flight_schedules.id` |
| day_of_week | VARCHAR | One of `MONDAY`..`SUNDAY` |

## 3.3 Modified table: `flights`

| Change | Detail |
|---|---|
| `schedule_id` column | Same `BIGINT NULL` column as before — now mapped via `@ManyToOne @JoinColumn(name = "schedule_id")` instead of a bare `Long` field. **No physical schema change** from the original revision of this module. |
| Constraint removed | Single-column `UNIQUE` on `flight_number` |
| Constraint added | Composite `UNIQUE (flight_number, departure_time)`, named `uk_flight_number_departure_time` |
| New columns | `created_by`, `updated_by` (VARCHAR(100), nullable), `version` (BIGINT, nullable) — auditing/optimistic-locking, same mechanism as 3.1 |

### ⚠️ Manual step likely required

`ddl-auto: update` adds new columns/tables but does not reliably drop constraints that already existed from a previous run, and it will not retroactively backfill a `NOT NULL` column (like `flight_schedules.schedule_code`) if rows already exist without one.

```sql
-- 1) Check what's currently on the flights table
SELECT conname, pg_get_constraintdef(oid)
FROM pg_constraint
WHERE conrelid = 'flights'::regclass AND contype = 'u';

-- If a unique constraint on flight_number alone still exists
-- (commonly named flights_flight_number_key), drop it:
ALTER TABLE flights DROP CONSTRAINT flights_flight_number_key;

-- Confirm/add the composite constraint if Hibernate hasn't created it:
ALTER TABLE flights
  ADD CONSTRAINT uk_flight_number_departure_time
  UNIQUE (flight_number, departure_time);

-- 2) If you already created any FlightSchedule rows before this revision,
-- they won't have a schedule_code and the new NOT NULL + UNIQUE constraint
-- will fail to apply. Since this is local/dev data, easiest is to drop and
-- let Hibernate recreate both schedule tables cleanly:
DROP TABLE IF EXISTS flight_schedule_operating_days;
DROP TABLE IF EXISTS flight_schedules;
-- restart flight-service so Hibernate recreates them with the new columns.
```

---

# 4. Domain Model

## 4.1 `Auditable` (new `@MappedSuperclass`)

`com.skybook.praveen.flightservice.entity.Auditable` — both `Flight` and `FlightSchedule` extend this instead of declaring their own `createdAt`/`updatedAt` and hand-rolled `@PrePersist`/`@PreUpdate` timestamp logic.

| Field | Type | Populated by |
|---|---|---|
| createdAt | LocalDateTime | `@CreatedDate` |
| updatedAt | LocalDateTime | `@LastModifiedDate` |
| createdBy | String | `@CreatedBy` |
| updatedBy | String | `@LastModifiedBy` |
| version | Long | `@Version` (Hibernate-managed optimistic lock) |

Wired up via `@EnableJpaAuditing` in the new `config/JpaAuditingConfig`, which also supplies the `AuditorAware<String>` bean. **Caveat:** `flight-service` has no Spring Security / JWT validation yet, so there's no real authenticated principal to stamp into `createdBy`/`updatedBy` — the bean currently returns a fixed `"system"` placeholder. Swap this for the real principal once JWT validation is added to this service.

## 4.2 `ScheduleStatus` enum

| Value | Meaning |
|---|---|
| `ACTIVE` | Schedule generates flights normally (manually or via the cron job) |
| `PAUSED` | Generation is skipped; already-generated flights are untouched |
| `CANCELLED` | Terminal. All not-yet-departed generated flights are cancelled along with it |
| `COMPLETED` | Set automatically once generation reaches `validTo`. Can be reactivated to `ACTIVE` by extending `validTo` |

## 4.3 `FlightSchedule` entity

Package: `com.skybook.praveen.flightservice.entity`. Extends `Auditable`.

| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| scheduleCode | String | **New.** Immutable, unique, system-generated at creation (`SCH-{origin}-{dest}-{id}`) |
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
| generationDaysAhead | Integer | **New.** Per-schedule generation horizon; defaults to 30 |
| statusReason | String (nullable) | **New.** Why the schedule is paused/cancelled |
| statusRemarks | String (nullable, max 500) | **New.** Free-text notes |

## 4.4 `Flight` entity changes

| Field | Change |
|---|---|
| schedule | **Changed.** Was `Long scheduleId`, now `@ManyToOne(fetch = LAZY) @JoinColumn(name = "schedule_id") FlightSchedule schedule`. Same physical column, real association. |
| flightNumber | No longer individually unique — composite `(flightNumber, departureTime)` constraint (unchanged from the prior revision). |

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
| generationDaysAhead | Integer | **New.** Optional, `@Positive` if supplied; defaults to 30 |

`scheduleCode` is **not** part of this request — it's always system-assigned and immutable, never client-suppliable.

Additional service-level checks (return `400`):
- `originAirportCode` must differ from `destinationAirportCode`.
- If `validTo` is present, it must be after `validFrom`.

## 5.2 `PauseFlightScheduleRequest` (new)

| Field | Type | Validation |
|---|---|---|
| reason | String | optional |
| remarks | String | optional |

Request body is itself optional on the `/pause` call — omit it entirely to pause without recording a reason.

## 5.3 `CancelFlightScheduleRequest` (new)

Same shape as `PauseFlightScheduleRequest` (`reason`, `remarks`, both optional). Body is optional on the `/cancel` call.

## 5.4 `ExtendFlightScheduleRequest`

| Field | Type | Validation |
|---|---|---|
| newValidTo | LocalDate | `@NotNull`, `@Future` |

Service-level checks: schedule must not be `CANCELLED` (409); `newValidTo` must be after the current `validTo` if one is set (400).

## 5.5 `FlightScheduleResponse`

```
id, scheduleCode, flightNumber, airlineCode, originAirportCode, destinationAirportCode,
departureTime, arrivalTime, operatingDays, validFrom, validTo, status,
lastGeneratedDate, generationDaysAhead, statusReason, statusRemarks,
createdBy, updatedBy, version, createdAt, updatedAt
```

## 5.6 `FlightResponse` (updated)

```
id, flightNumber, airlineCode, originAirportCode, destinationAirportCode,
departureTime, arrivalTime, status, scheduleId, createdBy, updatedBy, version,
createdAt, updatedAt
```

`scheduleId` is still exposed under that name for API stability — it's now derived from `flight.getSchedule().getId()` (null-safe) rather than being a raw stored column.

---

# 6. API Endpoints

Base path: `/api/flight-schedules`

## 6.1 Create Flight Schedule

`POST /api/flight-schedules` → `201 Created`

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
  "validTo": "2026-09-30",
  "generationDaysAhead": 60
}
```
(`generationDaysAhead` may be omitted — defaults to 30.)

Response `201`:
```json
{
  "id": 1,
  "scheduleCode": "SCH-LHR-JFK-000001",
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
  "generationDaysAhead": 60,
  "statusReason": null,
  "statusRemarks": null,
  "createdBy": "system",
  "updatedBy": "system",
  "version": 1,
  "createdAt": "2026-06-30T21:42:15.5076406",
  "updatedAt": "2026-06-30T21:42:15.5076406"
}
```

Errors: `400` on validation failure, same-origin/destination, or `validTo` before `validFrom`.

## 6.2 Get Flight Schedule By Id

`GET /api/flight-schedules/{id}` → `200` `FlightScheduleResponse` / `404`

## 6.3 Get All Flight Schedules

`GET /api/flight-schedules` → `200` `List<FlightScheduleResponse>`

## 6.4 Pause Flight Schedule

`PATCH /api/flight-schedules/{id}/pause`

Request body (optional):
```json
{ "reason": "Runway Maintenance", "remarks": "LHR runway 2 closed for resurfacing through August" }
```

Stops future generation; already-generated flights are untouched. `200` → updated `FlightScheduleResponse` with `status: "PAUSED"` and the reason/remarks recorded. Errors: `404`, `409` if already `CANCELLED`.

## 6.5 Resume Flight Schedule

`PATCH /api/flight-schedules/{id}/resume`

No body. `200` → `status: "ACTIVE"`, `statusReason`/`statusRemarks` cleared back to `null`. Errors: `404`, `409` if not currently `PAUSED`.

## 6.6 Cancel Flight Schedule

`PATCH /api/flight-schedules/{id}/cancel`

Request body (optional):
```json
{ "reason": "Route Discontinued", "remarks": "Commercial decision - route underperforming" }
```

Sets the schedule to `CANCELLED`, records the reason/remarks, and cancels every `Flight` generated from it that hasn't departed yet. `200` → updated `FlightScheduleResponse`. Errors: `404`.

## 6.7 Extend Flight Schedule

`PATCH /api/flight-schedules/{id}/extend`

Request body:
```json
{ "newValidTo": "2026-12-31" }
```

Pushes `validTo` further out; reactivates a `COMPLETED` schedule to `ACTIVE`. `200` → updated `FlightScheduleResponse`. Errors: `404`, `409` if `CANCELLED`, `400` if `newValidTo` isn't after the current `validTo`.

## 6.8 Generate Flights

`POST /api/flight-schedules/{id}/generate`

| Param | In | Type | Default | Notes |
|---|---|---|---|---|
| id | path | Long | — | |
| horizonDays | query | Integer | *(none — falls back to the schedule's own `generationDaysAhead`)* | Optional override for this one call |

`200` → `List<FlightResponse>` of only the newly created flights from this call (empty if not `ACTIVE`, or nothing left to generate). Idempotent.

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
    "createdBy": "system",
    "updatedBy": "system",
    "version": 0,
    "createdAt": "2026-06-30T22:10:00.123",
    "updatedAt": "2026-06-30T22:10:00.123"
  }
]
```

---

# 7. Flight Generation Algorithm

Implemented in `FlightScheduleServiceImpl.generateFlights(scheduleId, horizonDaysOverride)`:

1. Load the schedule. If its status isn't `ACTIVE`, return an empty list immediately.
2. Resolve `horizonDays`: the explicit `horizonDaysOverride` parameter if given, otherwise the schedule's own `generationDaysAhead`.
3. Compute the generation window:
   - `windowStart` = `lastGeneratedDate + 1 day` if generation has run before, otherwise `validFrom`. Clamped forward to today if it's in the past.
   - `windowEnd` = `windowStart + horizonDays`, clamped to `validTo` if set.
   - If `windowEnd` is before `windowStart`, the schedule has fully run its course — status flips to `COMPLETED`, nothing generated.
4. Walk every calendar date from `windowStart` to `windowEnd`. For each date whose `DayOfWeek` is in `operatingDays`:
   - `departureDateTime = date + departureTime`.
   - `arrivalDateTime`: same date if `arrivalTime` is after `departureTime`, otherwise the next date (overnight handling).
   - Skip if `existsByFlightNumberAndDepartureTime(flightNumber, departureDateTime)` already true — this is what makes the call idempotent.
   - Otherwise build a new `Flight` (`status = SCHEDULED`, `schedule` = this schedule) and add it to the batch.
5. Bulk-save all newly built flights.
6. Set `lastGeneratedDate = windowEnd`. Flip to `COMPLETED` if `validTo` has now been reached.
7. Return the newly created flights, mapped to `FlightResponse`.

`generateFlightsForAllActiveSchedules()` loops `findByStatus(ACTIVE)` and calls the above for each schedule with no override — so each one uses its own `generationDaysAhead`. This is what the cron job calls.

---

# 8. Scheduled Job

`FlightGenerationJob` (`com.skybook.praveen.flightservice.scheduler`):

```java
@Scheduled(cron = "0 0 1 * * *")
public void generateUpcomingFlights() {
    flightScheduleService.generateFlightsForAllActiveSchedules();
}
```

Runs once a day at **01:00 server time**. `@EnableScheduling` is on `FlightServiceApplication`. No new Maven dependency needed — `@Scheduled` comes from `spring-context`, already pulled in transitively by `spring-boot-starter-web`.

---

# 9. Error Handling

`GlobalExceptionHandler` maps exceptions to the standard error shape (`timestamp`, `status`, `error`, `message`, `path`):

| Exception | HTTP Status |
|---|---|
| `FlightNotFoundException` | 404 |
| `FlightScheduleNotFoundException` | 404 |
| `ObjectOptimisticLockingFailureException` (**new** — introduced by `@Version`) | 409, "This record was modified by another request. Please reload and try again." |
| `IllegalStateException` | 409 (invalid lifecycle transition) |
| `IllegalArgumentException` | 400 (bad input) |
| `MethodArgumentNotValidException` | 400 (bean validation failures) |

---

# 10. Files Added / Modified

**New files:**
```
entity/Auditable.java
config/JpaAuditingConfig.java
enums/ScheduleStatus.java
entity/FlightSchedule.java
dto/request/CreateFlightScheduleRequest.java
dto/request/ExtendFlightScheduleRequest.java
dto/request/PauseFlightScheduleRequest.java
dto/request/CancelFlightScheduleRequest.java
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
entity/Flight.java                  - extends Auditable; schedule_id is now a @ManyToOne FlightSchedule, not a raw Long;
                                       unique constraint moved to (flight_number, departure_time)
repository/FlightRepository.java    - existsByFlightNumberAndDepartureTime, findBySchedule_IdAndDepartureTimeAfter
service/FlightService.java          - removed unused no-arg schedule stub methods
service/impl/FlightServiceImpl.java - removed stub methods; validateFlightCreation now checks (flightNumber, departureTime)
dto/response/FlightResponse.java    - added scheduleId, createdBy, updatedBy, version
mapper/FlightMapper.java            - maps scheduleId from flight.getSchedule(), plus audit fields
exception/GlobalExceptionHandler.java - added handlers (see section 9)
FlightServiceApplication.java       - added @EnableScheduling
```

No changes were required to `pom.xml` — everything used (including JPA auditing) is already pulled in by `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, and `spring-boot-starter-validation`.

---

# 11. Manual Testing Guide

1. **Create a schedule** — `POST /api/flight-schedules` (6.1). Confirm the response includes a `scheduleCode` like `SCH-LHR-JFK-000001`.
2. **Generate flights** — `POST /api/flight-schedules/{id}/generate` (omit `horizonDays` to confirm it falls back to `generationDaysAhead`). Check `GET /api/flights` — generated rows have `scheduleId` set.
3. **Re-run generate** with the same horizon — should not duplicate flights (idempotency).
4. **Pause with a reason** — `PATCH /api/flight-schedules/{id}/pause` with `{"reason": "Weather", "remarks": "..."}`. Confirm `GET /api/flight-schedules/{id}` shows them. Call `/generate` again — expect `[]`.
5. **Resume** — `PATCH /api/flight-schedules/{id}/resume`. Confirm `statusReason`/`statusRemarks` are now `null`.
6. **Extend** — `PATCH /api/flight-schedules/{id}/extend` with `{"newValidTo": "..."}`.
7. **Cancel with a reason** — `PATCH /api/flight-schedules/{id}/cancel`, then `GET /api/flights/status/CANCELLED` — not-yet-departed flights tied to that schedule should appear there.
8. **Concurrency check** — fetch the same schedule in two clients, pause it in one, then try to resume the stale copy in the other; expect a `409` from the new optimistic-locking handler rather than a silent overwrite.
9. Swagger UI at `/swagger-ui.html` picks up `FlightScheduleController` automatically.

---

# 12. Deferred / Out of Scope

These were raised during design review but intentionally not implemented yet, to avoid adding complexity ahead of need:

- **Generation strategy (`AUTO` / `MANUAL`)** — letting an admin choose whether a schedule generates automatically via the cron job or only on manual trigger. Today every `ACTIVE` schedule is picked up by the daily job; there's no per-schedule opt-out. Worth adding once there's a real admin UI driving this.
- **`FlightStatusHistory` / `FlightDelayHistory` / `FlightGateHistory`** — append-only audit tables tracking every status/delay/gate change over time, instead of only the current state on the `Flight` row. This is a bigger addition (new entities, write-path changes throughout `FlightServiceImpl`) better suited to its own pass once the core flight/booking/check-in flow is end-to-end.

---

# 13. Known Limitations / Follow-Ups

- No authentication/authorization on these endpoints yet — schedule management should ultimately be `ADMIN`-only per the SRS. This is also why `createdBy`/`updatedBy` are currently stamped with a fixed `"system"` placeholder rather than a real principal.
- `flight-service`'s datasource must point at `skybook_flight`, not `skybook_auth` (pre-existing misconfiguration, unrelated to this module but affects where these tables land).
- No DB-level foreign key from `flights.schedule_id` to `flight_schedules.id`, or from `flight_schedule_operating_days.schedule_id` beyond what Hibernate's mapping implies.
- `departureTime`/`arrivalTime` are naive `LocalTime`/`LocalDateTime` with no timezone — fine for a single-timezone portfolio project, would need rework for multi-timezone realism.
- Soft delete / restore for schedules isn't implemented — cancelling is the only terminal state short of deleting the row directly.
