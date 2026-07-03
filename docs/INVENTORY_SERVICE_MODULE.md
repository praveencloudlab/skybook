# 🪑 SkyBook Inventory Service Module

---

## Project Information

| | |
|---|---|
| **Module** | `inventory-service` |
| **Sprint** | Sprint 4 |
| **Branch** | `feature/inventory-management` |
| **Group / Artifact** | `com.skybook.praveen` / `inventory-service` |
| **Base package** | `com.skybook.praveen.inventoryservice` |
| **Port** | `8084` |
| **Database** | `skybook_inventory` (PostgreSQL, `ddl-auto: update`) |
| **Java / Spring Boot** | 21 / 3.5.16 (parent-managed) |
| **Status** | Implemented — 83 unit tests green |

---

# Table of Contents

1. [Overview](#1-overview)
2. [Architecture — Layered Design](#2-architecture--layered-design)
3. [Domain Model / Aggregates](#3-domain-model--aggregates)
4. [Enums & State Machines](#4-enums--state-machines)
5. [Seat Holds — TTL & Expiry](#5-seat-holds--ttl--expiry)
6. [The Count Invariant & Concurrency](#6-the-count-invariant--concurrency)
7. [API Endpoints](#7-api-endpoints)
8. [Cross-Service Integration & Event Flow](#8-cross-service-integration--event-flow)
9. [Domain Services — Scope for V1](#9-domain-services--scope-for-v1)
10. [Shared Code (`skybook-common` additions)](#10-shared-code-skybook-common-additions)
11. [Configuration](#11-configuration)
12. [Deferred / Out of Scope](#12-deferred--out-of-scope)
13. [Known Risks / Open Questions](#13-known-risks--open-questions)
14. [Build Order (As Executed)](#14-build-order-as-executed)
15. [Implementation Notes](#15-implementation-notes)
16. [Files Added / Modified](#16-files-added--modified)
17. [Test Suite](#17-test-suite)
18. [Manual Testing Guide](#18-manual-testing-guide)

---

# 1. Overview

`inventory-service` owns **which seats exist and which seats can be sold** for every flight:

- **Fleet master data** — aircraft (airframes) and their seat maps.
- **Per-flight sellability** — one `FlightInventory` record per flight with denormalized seat counts.
- **Seat holds** — time-boxed soft locks taken while a booking is in progress (default TTL 15 minutes, swept by a scheduled job).
- **Seat reservations** — hard commitments, normally created by confirming a hold.
- **Audit trail** — an append-only `InventoryHistory` per inventory, mirroring `BookingHistory`.

What it deliberately does **not** own:

- Flights and schedules → `flight-service` (referenced by `flightId` only).
- Bookings and passengers → `booking-service` (referenced by `bookingId` / `bookingPassengerId` only).
- Notifications → published as Kafka events; no direct calls.

The design intentionally mirrors `booking-service` (Sprint 3): same layering, same state-machine pattern, same facade philosophy, same test style. Anyone who has read `BOOKING_SERVICE_MODULE.md` should find this module unsurprising.

---

# 2. Architecture — Layered Design

```
controller  →  facade  →  service (impl)  →  repository
                 │             │
                 │             └── domain (state machine, validators, calculator, generator)
                 │
                 ├── client  (Feign → flight-service)
                 └── producer (Kafka → skybook.inventory.events)
```

| Package | Responsibility |
|---|---|
| `controller` | REST endpoints, `@Valid`, HTTP status codes. No logic. |
| `facade` | Orchestration only: flight validation before inventory creation, Kafka publishing after commit. **Not** `@Transactional`. |
| `service` / `service.impl` | Transactions, aggregate lookups, count bookkeeping, exception translation. |
| `domain` | Pure business rules — no repository or Spring-web dependencies; unit-testable without mocks. |
| `repository` | Spring Data JPA interfaces, derived queries only (no `@Query`). |
| `entity` / `enums` | JPA aggregates + status vocabularies. |
| `dto.request` / `dto.response` | Records; bean validation on requests. |
| `mapper` | Static final mappers (no MapStruct), same as `BookingMapper`. |
| `client` | Feign client + anti-corruption DTO for flight-service. |
| `producer` | `InventoryEventProducer` → Kafka. |
| `consumer` | **Deferred** — `BookingEventConsumer` planned, not built (see §12). |
| `exception` | Domain exceptions + `GlobalExceptionHandler` (`@RestControllerAdvice`). |
| `config` | JPA auditing, Kafka producer factory, permit-all security placeholder. |
| `scheduler` | `SeatHoldExpiryJob` — sweeps expired holds. |

**Facade philosophy (inherited from `BookingFacade`):** only operations with something to orchestrate go through `InventoryFacade` — `createInventory` (validates the flight first), and the four seat operations (each publishes an event). Reads, search, history, close/reopen call services directly from the controller. A facade method that adds nothing is ceremony.

**Why the facade is not `@Transactional`:** each service method commits before returning, so publishing to Kafka afterwards behaves like `AFTER_COMMIT` without the indirection of `@TransactionalEventListener`. A transactional outbox is the upgrade path if delivery guarantees ever need to be stronger (§13).

---

# 3. Domain Model / Aggregates

Two aggregate roots:

```
Aircraft (root)                      FlightInventory (root)
  └── AircraftSeat (1..N)              ├── InventoryHistory (append-only, cascade)
                                       ├── SeatHold (N, saved independently)
                                       └── SeatReservation (N, saved independently)
```

All entities extend `com.skybook.praveen.common.entity.Auditable` (createdAt/updatedAt/createdBy/updatedBy + `@Version`).

## 3.1 `Aircraft` (aggregate root)

A physical airframe in the fleet.

| Field | Notes |
|---|---|
| `registrationNumber` | Unique, immutable, ≤10 chars — e.g. `VT-SKB`. The airline-wide identifier. |
| `manufacturer`, `model` | e.g. `Airbus` / `A320neo`. |
| `totalSeats` | **Denormalized** — recomputed by `SeatMapGenerator` whenever seats change. Never set by callers. |
| `status` | `AircraftStatus`, string-persisted. Defaults to `ACTIVE` via `@PrePersist`. |
| `seats` | `OneToMany`, cascade + orphanRemoval, `@OrderBy("rowNumber ASC, seatNumber ASC")`. Seats persist via cascade — same pattern as `Booking.passengers`. |

## 3.2 `AircraftSeat`

One physical seat on one airframe — the seat-map building block.

| Field | Notes |
|---|---|
| `seatNumber` | e.g. `12A`. Unique **per aircraft** — composite constraint `uk_aircraft_seat_number (aircraft_id, seatNumber)`. `12A` exists on every A320. |
| `rowNumber` | Denormalized from `seatNumber` for cheap row queries/sorting. |
| `seatType` | `SeatType` — cabin class, mirrors booking's `TravelClass` 1:1 by name. |
| `position` | `SeatPosition` — WINDOW / MIDDLE / AISLE. |
| `status` | `AircraftSeatStatus` — **physical/config state only**; says nothing about per-flight occupancy. |
| `exitRow` | Restriction flag; enforced by `SeatAllocationValidator.validateExitRowAllowed` once passenger eligibility data crosses the wire. |

## 3.3 `FlightInventory` (aggregate root)

Sellable-seat state for one flight. The concurrency hot-spot.

| Field | Notes |
|---|---|
| `flightId` | Unique, immutable, reference-only (flight-service id). One inventory per flight. |
| `aircraft` | `ManyToOne` — the operating airframe = seat-map source. |
| `status` | `InventoryStatus` — OPEN / SOLD_OUT / CLOSED. |
| `totalSeats` | Count of the aircraft's ACTIVE seats at creation time. Fixed. |
| `availableSeats`, `heldSeats`, `reservedSeats`, `blockedSeats` | Denormalized snapshots — see §6 for the invariant. |
| `history` | `OneToMany` cascade, `@OrderBy("changedAt ASC")` — persists via cascade like `Booking.history`. |

## 3.4 `SeatHold`

A time-boxed soft lock on one seat of one flight.

| Field | Notes |
|---|---|
| `flightInventory`, `aircraftSeat` | `ManyToOne`, both required. |
| `bookingId` | Reference-only, immutable — correlation key for confirm/release. |
| `status` | `SeatHoldStatus` — ACTIVE is the only non-terminal state. |
| `heldAt`, `expiresAt` | `expiresAt = heldAt + TTL` via `SeatHoldExpiryCalculator`. |

Indexes: `(status, expiresAt)` for the expiry-job sweep; `(bookingId)` for correlation lookups.

> **Why no unique constraint for "one ACTIVE hold per seat":** terminal rows (RELEASED/EXPIRED/CONFIRMED) share the same seat, so a plain DB constraint can't express it (would need a partial index — see §13). It is enforced in the service layer, and races are caught by `FlightInventory`'s `@Version`: every hold/release bumps the counts, so two racing holds collide on optimistic lock.

## 3.5 `SeatReservation`

The hard counterpart of `SeatHold` — a committed seat assignment.

| Field | Notes |
|---|---|
| `bookingId` | Required, immutable. |
| `bookingPassengerId` | Nullable — a reservation can exist before seats are assigned to named passengers. |
| `originatingHold` | `OneToOne`, nullable — audit link to the confirmed hold. Null for direct (hold-less) reservations, e.g. airport counter flows. |
| `status` | RESERVED → CANCELLED only. |
| `reservedAt`, `cancelledAt` | `cancelledAt` set exactly once by the state machine. |

Same service-layer uniqueness argument as holds.

## 3.6 `InventoryHistory` (audit trail)

Append-only, written by `InventoryStateMachine.recordHistory` in the same transaction as the change it records — never updated, never deleted. Persisted for free via `FlightInventory`'s cascade.

| Field | Notes |
|---|---|
| `historyType` | `InventoryHistoryType` (9 values). |
| `aircraftSeat` | Nullable — null for inventory-level events (created/closed/reopened/sold-out). |
| `bookingId` | Nullable — the booking that triggered the change, if any. |
| `details` | Free text ≤500 chars, never parsed. |
| `changedAt` | Set at creation; drives `@OrderBy`. |

## 3.7 Flight / Booking — no local entities

Same convention as booking-service's §3.7: no local `Flight` or `Booking` tables. Everything crosses service boundaries by id only. The only flight data this service ever sees is the `FlightDetails` anti-corruption record (§8).

---

# 4. Enums & State Machines

All enum fields persist as `@Enumerated(EnumType.STRING)` — a lesson carried over from the Sprint 3 booking bug where a missing annotation stored ordinals.

## 4.1 `AircraftStatus` (on `Aircraft`)

`ACTIVE → MAINTENANCE / GROUNDED / RETIRED` (any direction between the first three; **RETIRED is terminal**, enforced in `AircraftServiceImpl`, not the state machine — it's master data, not a transactional lifecycle).

Inventory can only be *created* against an ACTIVE aircraft, and holds/reservations require the aircraft to still be ACTIVE (`SeatAllocationValidator`).

## 4.2 `AircraftSeatStatus` (on `AircraftSeat`)

`ACTIVE ↔ BLOCKED ↔ INOPERATIVE` — freely switchable (no terminal state). Physical/configuration state only.

## 4.3 `InventoryStatus` (on `FlightInventory`) — state machine

| From | Allowed to |
|---|---|
| OPEN | SOLD_OUT, CLOSED |
| SOLD_OUT | OPEN, CLOSED |
| CLOSED | OPEN |

- OPEN → SOLD_OUT happens **automatically** when the last available seat is taken (`markSoldOutIfFull`).
- SOLD_OUT → OPEN happens **automatically** when any seat returns to the pool (`returnSeatToPool`).
- CLOSED is **manual only** (schedule change, operational hold) via `PATCH /close`, reversed via `/reopen`.

## 4.4 `SeatHoldStatus` (on `SeatHold`) — state machine

| From | Allowed to |
|---|---|
| ACTIVE | CONFIRMED, RELEASED, EXPIRED |
| CONFIRMED / RELEASED / EXPIRED | — (terminal) |

Transition → history mapping: RELEASED → `HOLD_RELEASED`, EXPIRED → `HOLD_EXPIRED`, CONFIRMED → `SEAT_RESERVED`.

## 4.5 `SeatReservationStatus` (on `SeatReservation`) — state machine

`RESERVED → CANCELLED` (terminal). Cancel sets `cancelledAt` and records `RESERVATION_CANCELLED`.

## 4.6 `InventoryStateMachine`

Single `@Component` owning all three transition tables (`EnumMap` statics), exactly like `BookingStateMachine`:

- No repository dependency — only mutates entities handed to it.
- Every transition appends an `InventoryHistory` row in-memory; persistence happens via cascade when the caller saves.
- `recordHistory` is public so services can record **non-transition** events (`SEAT_HELD`, `INVENTORY_CREATED`).
- Invalid transitions throw `IllegalStateException` → 409 via the handler.

## 4.7 `InventoryHistoryType`

`INVENTORY_CREATED, SEAT_HELD, HOLD_RELEASED, HOLD_EXPIRED, SEAT_RESERVED, RESERVATION_CANCELLED, INVENTORY_CLOSED, INVENTORY_REOPENED, INVENTORY_SOLD_OUT`

---

# 5. Seat Holds — TTL & Expiry

**Flow:** booking-service asks for a hold while the customer completes payment. The hold blocks the seat for `inventory.hold.ttl-minutes` (default **15**). Then exactly one of three things happens:

1. **Confirmed** → `POST /api/reservations` with the `holdId` (or just booking+seat) — hold becomes CONFIRMED, a `SeatReservation` is created, `heldSeats-1 / reservedSeats+1` (`availableSeats` untouched — it was decremented at hold time).
2. **Released** → `POST /api/inventory/release` — seat returns to the pool.
3. **Expired** → `SeatHoldExpiryJob` sweeps every `inventory.hold.sweep-interval-ms` (default 60 s) via `findByStatusAndExpiresAtBefore(ACTIVE, now)` (backed by the `(status, expiresAt)` index), transitions each to EXPIRED and returns seats to the pool.

**Boundary semantics:** `isExpired` uses `isAfter`, so a hold expiring at 10:15:00 is still valid *at* 10:15:00.

**Expired-hold confirmation race:** if a confirm request arrives after `expiresAt` but before the sweep, `SeatReservationServiceImpl` checks the timestamp itself and throws `SeatHoldExpiredException` → **410 Gone** (not 409 — retrying the same hold can never succeed; the caller must take a fresh hold).

`SeatHoldExpiryCalculator` owns the TTL policy as its own class so it is per-environment configurable and the boundary decision is unit-testable in isolation. `@EnableScheduling` lives on `SeatHoldExpiryJob` itself — owned by the one component that uses it.

---

# 6. The Count Invariant & Concurrency

Every `FlightInventory` maintains, inside the same transaction as the hold/reservation rows:

```
availableSeats + heldSeats + reservedSeats + blockedSeats == totalSeats
```

| Operation | available | held | reserved |
|---|---|---|---|
| hold | −1 | +1 | |
| release hold / expire | +1 | −1 | |
| confirm hold → reservation | | −1 | +1 |
| direct reservation | −1 | | +1 |
| cancel reservation | +1 | | −1 |

- `totalSeats` = count of the aircraft's ACTIVE seats at inventory creation; `blockedSeats` = per-flight operational blocks (crew rest etc.), from the create request.
- All bookkeeping lives in `InventoryServiceImpl` (`returnSeatToPool`, `markSoldOutIfFull` — package-private, reused by `SeatReservationServiceImpl` which injects the **impl**, not the interface, precisely so the invariant has exactly one home).
- **Concurrency:** guarded by `Auditable`'s `@Version` on `FlightInventory`. Every seat operation mutates the counts, so two racing operations on the same flight collide on optimistic lock; the loser gets `ObjectOptimisticLockingFailureException` → 409 with a "reload and try again" message. No pessimistic locks, no serializable isolation.

---

# 7. API Endpoints

## Aircraft — `/api/aircraft`

| Method | Path | Notes |
|---|---|---|
| POST | `/api/aircraft` | 201. Duplicate registration → 409. |
| GET | `/api/aircraft` | All aircraft. |
| GET | `/api/aircraft/{id}` | 404 if unknown. |
| GET | `/api/aircraft/registration/{reg}` | Lookup by tail number. |
| GET | `/api/aircraft/status/{status}` | Filter by `AircraftStatus`. |
| PATCH | `/api/aircraft/{id}/status?status=X` | RETIRED is terminal → 409. |

## Seat maps — `/api/aircraft/{aircraftId}`

| Method | Path | Notes |
|---|---|---|
| POST | `/seats` | Add one seat. 201. Duplicate → 400. |
| POST | `/seat-map` | Bulk create (≤1000), all-or-nothing. 201. |
| GET | `/seat-map` | Full map, row/seat ordered. |
| GET | `/seats/status/{status}` | Filter. 404 for unknown aircraft (not an empty list). |
| PATCH | `/seats/{seatNumber}/status?status=X` | Physical status change. |

## Inventory — `/api/inventory`

| Method | Path | Notes |
|---|---|---|
| POST | `/api/inventory` | **Facade** — validates flight via flight-service first. 201. Duplicate flight → 409; unknown flight → 404; flight-service down → 502; cancelled flight → 400. |
| GET | `/flight/{flightId}` | Inventory + counts. |
| POST | `/search` | Null-means-no-filter criteria (`flightId`, `aircraftId`, `status`, `minAvailableSeats`). |
| GET | `/flight/{flightId}/history` | Full audit trail, oldest first. |
| POST | `/hold` | **Facade** — publishes `SEAT_HELD`. 201. Already held/reserved → 409. |
| POST | `/release` | **Facade** — publishes `SEAT_RELEASED`. Wrong booking → 409. |
| PATCH | `/flight/{flightId}/close?reason=` | Manual stop-sell. |
| PATCH | `/flight/{flightId}/reopen?reason=` | Reverse. |

## Reservations — `/api/reservations`

| Method | Path | Notes |
|---|---|---|
| POST | `/api/reservations` | **Facade** — confirm hold or direct-reserve. 201. Expired hold → **410**. |
| POST | `/api/reservations/cancel` | **Facade** — publishes `RESERVATION_CANCELLED`. |
| GET | `/booking/{bookingId}` | All reservations for a booking (any status). |
| GET | `/flight/{flightId}` | Active (RESERVED) reservations for a flight. |

## Error contract

`GlobalExceptionHandler` → common `ErrorResponse` record (timestamp/status/error/message/path):

| Exception | HTTP |
|---|---|
| `*NotFoundException` (aircraft, seat, inventory, flight-for-inventory) | 404 |
| `SeatAlreadyHeld` / `SeatAlreadyReserved` / `SeatNotAvailable` / `InventoryConflict` | 409 |
| `SeatHoldExpiredException` | **410** |
| `FlightServiceUnavailableException` | 502 |
| `ObjectOptimisticLockingFailureException`, `IllegalStateException` | 409 |
| `IllegalArgumentException`, `MethodArgumentNotValidException` | 400 |

Swagger UI: `http://localhost:8084/swagger-ui.html` · API docs: `/api-docs` · Actuator: `/actuator/health`.

---

# 8. Cross-Service Integration & Event Flow

## Synchronous (Feign) — outbound only

`FlightServiceClient` → `GET {flight-service.base-url}/api/flights/{id}`, used **only** before inventory creation. Feign exceptions are translated at the client boundary (`NotFound` → `FlightNotFoundForInventoryException`, anything else → `FlightServiceUnavailableException`) so nothing downstream knows Feign exists.

`FlightDetails` is a deliberate **subset** record (`@JsonIgnoreProperties(ignoreUnknown = true)`) — anti-corruption, decoupled from flight-service's DTO evolution. `status` is a plain `String` because this service only ever checks `"CANCELLED"`.

The Feign client declares `contextId = "inventoryFlightServiceFeignClient"` to avoid a bean-name collision with booking-service's client of the same logical `name` in any shared test context.

## Asynchronous (Kafka) — topic `skybook.inventory.events`

`InventoryEventProducer` publishes `InventoryEvent` (shared contract, §10) after commit:

| Trigger | Event type |
|---|---|
| Inventory created | `INVENTORY_CREATED` |
| Hold placed | `SEAT_HELD` |
| Hold released | `SEAT_RELEASED` |
| Hold expired | `HOLD_EXPIRED` * |
| Reservation made | `SEAT_RESERVED` |
| Reservation cancelled | `RESERVATION_CANCELLED` |

\* `HOLD_EXPIRED` is defined in the contract but **not yet published** — the expiry job runs inside the service layer, below the facade where publishing lives. See §13.

## Intended booking flow (consumer side deferred)

```
booking created (CREATED)
    → booking-service calls POST /api/inventory/hold        (seat soft-locked, 15m)
payment succeeds → booking CONFIRMED
    → booking-service calls POST /api/reservations           (hold confirmed)
payment fails / abandoned
    → hold expires via SeatHoldExpiryJob (or explicit /release)
booking cancelled later
    → booking-service calls POST /api/reservations/cancel    (seat back in pool)
```

The `BookingEventConsumer` (react to `skybook.booking.events` instead of/alongside REST calls) is planned but **not built** — marked "later only" in the sprint plan.

---

# 9. Domain Services — Scope for V1

All five are pure `@Component`s with no repository dependencies — unit-testable with plain objects.

| Class | Role |
|---|---|
| `InventoryStateMachine` | Three transition tables + history recording (§4.6). |
| `SeatAvailabilityChecker` | **Boolean** answers for query flows. Occupancy flags passed in by the caller so it stays dependency-free. |
| `SeatAllocationValidator` | **Throwing** twin of the checker for command flows — precise `IllegalStateException` reasons (aircraft status, inventory status, seat status, exit-row eligibility hook). |
| `SeatHoldExpiryCalculator` | Owns the TTL policy; `calculateExpiry` + boundary-exact `isExpired`. |
| `SeatMapGenerator` | Request → `AircraftSeat` entities; enforces map-level rules bean validation can't see (duplicates within a batch, collisions with existing seats); maintains `Aircraft.totalSeats`. Failed batches add nothing (list materialized before `addAll`). |

Checker/validator split rationale: search endpoints want `false`, command endpoints want a *reason*. One rule set, two presentations.

---

# 10. Shared Code (`skybook-common` additions)

Two new files (additive only — no existing classes touched):

- `event/InventoryEvent` — Lombok class mirroring `BookingEvent`: `type`, `flightId`, `seatNumber` (null for inventory-level events), `bookingId` (null likewise), `details`.
- `event/InventoryEventType` — `INVENTORY_CREATED, SEAT_HELD, SEAT_RELEASED, HOLD_EXPIRED, SEAT_RESERVED, RESERVATION_CANCELLED`.

`KafkaTopics.INVENTORY_EVENTS` (`skybook.inventory.events`) already existed. Reused as-is: `Auditable`, `ErrorResponse`.

---

# 11. Configuration

`application.yml` highlights:

```yaml
server.port: 8084
spring.datasource.url: jdbc:postgresql://localhost:5432/skybook_inventory
spring.jpa.hibernate.ddl-auto: update
spring.kafka.bootstrap-servers: localhost:9092
flight-service.base-url: http://localhost:8082
inventory.hold.ttl-minutes: 15          # hold TTL
inventory.hold.sweep-interval-ms: 60000 # expiry-job cadence
```

Config classes:

- `JpaAuditingConfig` — `@EnableJpaAuditing`, auditor hardcoded to `"system"` until JWT validation lands (same TODO as booking/flight services).
- `KafkaProducerConfig` — `KafkaTemplate<String, InventoryEvent>`, String key / JSON value serializers.
- `SecurityConfig` — permit-all placeholder, csrf disabled; swap for JWT authorization when auth-service tokens are verifiable.
- No `OpenFeignConfig` — `@EnableFeignClients` on `InventoryServiceApplication` covers it (booking-service does the same).

---

# 12. Deferred / Out of Scope

- **`BookingEventConsumer`** — explicitly "later only" in the sprint plan. The `consumer` package doesn't exist yet.
- **`HOLD_EXPIRED` event publishing** — type defined, wiring pending (§13).
- **booking-service → inventory-service integration** — booking does not yet call hold/reserve during its lifecycle; that's the headline item for the next sprint.
- **Exit-row passenger eligibility** — validator hook exists (`validateExitRowAllowed`); passenger age/mobility data doesn't cross the wire yet, so no caller asserts it.
- **Per-flight seat blocking by seat number** — only a numeric `blockedSeats` count exists; blocking *specific* seats per flight is future work.
- **Seat-map versioning / aircraft swaps** — changing an aircraft's seat map after inventories exist against it is unhandled (§13).
- **JWT validation, real `createdBy`** — placeholder security, auditor = `"system"`.
- **DB-backed integration tests** — Testcontainers deps are in the POM (mirroring booking-service) but no integration tests are written yet.
- **Pagination** — list endpoints return full lists; acceptable at current volumes.

---

# 13. Known Risks / Open Questions

1. **Uniqueness under lost-update, belt-and-braces** — the "one ACTIVE hold/reservation per seat" rule rests entirely on the `@Version` collision argument (§6). A PostgreSQL **partial unique index** (`CREATE UNIQUE INDEX ... ON seat_holds (flight_inventory_id, aircraft_seat_id) WHERE status = 'ACTIVE'`) would make the DB the backstop; not expressible in JPA annotations, needs a migration tool (Flyway is absent from the whole project so far).
2. **`HOLD_EXPIRED` events are silent** — expiry happens in the service/scheduler layer; events publish from the facade. Booking-service will eventually need to know its hold died (release the pending booking). Options: publish from the job directly, or route the job through the facade.
3. **Aircraft swap / seat-map edits after inventory creation** — `totalSeats` snapshots at creation; later seat changes (or swapping the airframe) desync the counts. Needs a rebuild/reconcile operation.
4. **`markSoldOutIfFull` counts holds as sold** — a flight fully covered by *holds* flips to SOLD_OUT; expiries flip it back. Correct behavior, but search results will flicker for high-demand flights.
5. **Kafka publish failures are silent** — `kafkaTemplate.send` result is not awaited (same as booking-service). Outbox pattern is the known upgrade path.
6. **Clock skew** — hold expiry uses application-server `LocalDateTime.now()`; fine for a single node, wrong for multi-node with skew. `Instant` + DB time is the stricter alternative.
7. **In-memory search** — `findAll().stream().filter(...)` is fine at one-row-per-flight volumes, but revisit if the fleet/schedule grows (Specifications or derived queries).

---

# 14. Build Order (As Executed)

1. Enums (all 8) → 2. `Aircraft` → 3. `AircraftSeat` (+ `seats` collection) → 4. `FlightInventory` → 5. `SeatHold` → 6. `SeatReservation` → 7. `InventoryHistory` (+ `history` collection) → 8. Repositories (6) → 9. Response DTOs (7) + mappers (6), then request DTOs (8) → 10. Domain services (5) → 11. Exceptions (11) + handler → 12. Services: `AircraftService` → `AircraftSeatService` → `InventoryService` → `SeatReservationService` → 13. Client + producer + facade + controllers (4) + config (3) + scheduler → 14. Tests (§17).

Discipline: one class at a time (batched by agreement in the later, lower-risk layers), compile after every step, commit only on green. Every compile in the sprint was green on first run except one test-classpath issue (stale `skybook-common` in the local repo — fixed with `-am`, not a code change).

---

# 15. Implementation Notes

Decisions that deviate from, or add to, the original plan:

- **Two extra exceptions** beyond the planned nine: `FlightNotFoundForInventoryException` (404) and `FlightServiceUnavailableException` (502) — required by the Feign client boundary, mirroring booking-service.
- **`SeatHoldExpiredException` → 410 Gone**, not 409 — retrying an expired hold can never succeed; the distinct status tells callers to take a fresh hold.
- **Grouped exception handlers** — one handler per HTTP status with multi-exception `@ExceptionHandler` arrays, rather than booking's one-per-class. Same behavior, less boilerplate.
- **`SeatReservationServiceImpl` injects `InventoryServiceImpl` (the impl)** — deliberate: the count invariant and `returnSeatToPool`/`markSoldOutIfFull` live in exactly one class. The trade (impl coupling between two siblings in the same module) was judged cheaper than duplicating count logic.
- **`OpenFeignConfig` dropped** from the plan — `@EnableFeignClients` on the application class is sufficient (booking precedent).
- **`SeatType` mirrors `TravelClass` by name** so cross-service payloads translate 1:1 without a mapping table.
- **Single-seat add funnels through `SeatMapGenerator`** with a one-element list — duplicate rules exist in one place only.
- **`getSeatsByStatus` 404s for unknown aircraft** instead of returning an empty list — an empty list would be indistinguishable from "aircraft exists, no such seats".
- **Direct (hold-less) reservations allowed** — supports counter-style flows; `originatingHold` is null and history records "Direct reservation (no hold)".

---

# 16. Files Added / Modified

## Modified
- `backend/pom.xml` — `<module>inventory-service</module>`

## Added — `skybook-common`
- `event/InventoryEvent.java`, `event/InventoryEventType.java`

## Added — `inventory-service` (main, 62 files incl. module files)

```
pom.xml
src/main/resources/application.yml
InventoryServiceApplication.java
enums/      AircraftStatus, AircraftSeatStatus, SeatType, SeatPosition,
            InventoryStatus, SeatHoldStatus, SeatReservationStatus, InventoryHistoryType
entity/     Aircraft, AircraftSeat, FlightInventory, SeatHold, SeatReservation, InventoryHistory
repository/ AircraftRepository, AircraftSeatRepository, FlightInventoryRepository,
            SeatHoldRepository, SeatReservationRepository, InventoryHistoryRepository
dto/request/  CreateAircraftRequest, CreateAircraftSeatRequest, CreateSeatMapRequest,
              CreateFlightInventoryRequest, HoldSeatRequest, ReserveSeatRequest,
              ReleaseSeatRequest, InventorySearchRequest
dto/response/ AircraftResponse, AircraftSeatResponse, SeatMapResponse, FlightInventoryResponse,
              SeatHoldResponse, SeatReservationResponse, InventoryHistoryResponse
mapper/     AircraftMapper, AircraftSeatMapper, FlightInventoryMapper,
            SeatHoldMapper, SeatReservationMapper, InventoryHistoryMapper
domain/     InventoryStateMachine, SeatAvailabilityChecker, SeatHoldExpiryCalculator,
            SeatMapGenerator, SeatAllocationValidator
service/    AircraftService, AircraftSeatService, InventoryService, SeatReservationService
service/impl/ (four implementations)
facade/     InventoryFacade
client/     FlightServiceClient, FlightServiceFeignClient, FlightDetails
producer/   InventoryEventProducer
exception/  11 exceptions + GlobalExceptionHandler
config/     JpaAuditingConfig, KafkaProducerConfig, SecurityConfig
scheduler/  SeatHoldExpiryJob
```

## Added — tests (9 files, §17)

---

# 17. Test Suite

**83 tests, 0 failures** (`mvn test -pl inventory-service -am`).

| Class | Tests | Covers |
|---|---|---|
| `InventoryStateMachineTest` | 14 | Exhaustive golden transition tables for all three machines; history side effects; terminal-state rejections; `recordHistory`. |
| `SeatHoldExpiryCalculatorTest` | 5 | TTL math, configurability, boundary-exact expiry (valid *at* the boundary). |
| `SeatAvailabilityCheckerTest` | 8 | Full condition matrix (inventory status × aircraft status × seat status × occupancy × availability). |
| `SeatAllocationValidatorTest` | 7 | Pass/throw pairs incl. exit-row eligibility. |
| `SeatMapGeneratorTest` | 6 | Field mapping, totalSeats recount, in-batch duplicates, collisions, failed-batch atomicity. |
| `AircraftServiceImplTest` | 6 | Create/duplicate/not-found, RETIRED terminal. |
| `AircraftSeatServiceImplTest` | 7 | Add/bulk all-or-nothing/seat-map context/status update/404s. |
| `InventoryServiceImplTest` | 19 | Create derivations + 5 rejection paths; hold flow incl. SOLD_OUT auto-flip; release incl. reopen + cross-booking conflict; expiry sweep restores counts; close/reopen walk. |
| `SeatReservationServiceImplTest` | 13 | Hold-confirm (explicit id + auto-resolve), someone-else's-hold, expired hold (410 path), unknown/mismatched holdId, direct reservation + SOLD_OUT flip, cancel + reopen + cross-booking conflict. |

**Testing philosophy:** repositories are the *only* mocks. Domain collaborators run real, and `SeatReservationServiceImplTest` wires a real `InventoryServiceImpl` over the same mocks — so the count invariant is exercised across the service boundary, not stubbed away.

Not covered yet: controllers (thin), facade (thin), Kafka producer, Feign client, context-load smoke test, DB integration (Testcontainers ready in POM).

---

# 18. Manual Testing Guide

Prerequisites: PostgreSQL with `skybook_inventory` created, Kafka on `localhost:9092`, flight-service on `8082` with at least one flight (id `1` below).

```bash
# 1. Aircraft
curl -X POST localhost:8084/api/aircraft -H "Content-Type: application/json" \
  -d '{"registrationNumber":"VT-SKB","manufacturer":"Airbus","model":"A320neo"}'

# 2. Seat map (3 seats)
curl -X POST localhost:8084/api/aircraft/1/seat-map -H "Content-Type: application/json" \
  -d '{"seats":[
    {"seatNumber":"1A","rowNumber":1,"seatType":"BUSINESS","position":"WINDOW"},
    {"seatNumber":"1C","rowNumber":1,"seatType":"BUSINESS","position":"AISLE"},
    {"seatNumber":"12A","rowNumber":12,"seatType":"ECONOMY","position":"WINDOW","exitRow":true}]}'

# 3. Inventory for flight 1 (validates against flight-service first)
curl -X POST localhost:8084/api/inventory -H "Content-Type: application/json" \
  -d '{"flightId":1,"aircraftId":1}'

# 4. Hold 12A for booking 42  → expect availableSeats 2, heldSeats 1
curl -X POST localhost:8084/api/inventory/hold -H "Content-Type: application/json" \
  -d '{"flightId":1,"seatNumber":"12A","bookingId":42}'

# 5. Second hold on the same seat → expect 409
# 6. Confirm into a reservation → heldSeats 0, reservedSeats 1
curl -X POST localhost:8084/api/reservations -H "Content-Type: application/json" \
  -d '{"flightId":1,"seatNumber":"12A","bookingId":42}'

# 7. Verify counts + audit trail
curl localhost:8084/api/inventory/flight/1
curl localhost:8084/api/inventory/flight/1/history

# 8. Cancel → seat back in pool
curl -X POST localhost:8084/api/reservations/cancel -H "Content-Type: application/json" \
  -d '{"flightId":1,"seatNumber":"12A","bookingId":42,"reason":"manual test"}'

# 9. Expiry: hold a seat, wait >15m (or set inventory.hold.ttl-minutes: 1),
#    watch SeatHoldExpiryJob log "expired 1 hold(s)" and counts restore.

# 10. Kafka: watch events arrive
kafka-console-consumer --bootstrap-server localhost:9092 --topic skybook.inventory.events --from-beginning
```

Swagger UI at `http://localhost:8084/swagger-ui.html` covers everything above interactively.

---

*Sprint 4 — feature/inventory-management. 83 tests green. Ready for review/merge and Sprint 5 (booking ↔ inventory integration).*
