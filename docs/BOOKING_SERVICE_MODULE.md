# 🎫 SkyBook Booking Service Module

---

## Project Information

| Property | Value |
|----------|-------|
| Project | SkyBook Airline Reservation System |
| Module | Booking Service (Sprint 3) |
| Version | 1.0.0 (implemented) |
| Status | **Implemented** - entities, domain services, repositories, service/facade layers, controller, Kafka notification flow, and unit tests are built. Not yet run against a live database/Kafka broker in this environment - see section 15. |
| Java | 21 |
| Spring Boot | 3.5.x |
| Database | PostgreSQL (new schema, e.g. `skybook_booking`) |
| Build Tool | Maven (new module under `backend/`) |
| Author | Praveen Somireddy |

---

# Table of Contents

1. Overview
2. Architecture — Layered Design
3. Domain Model / Aggregate
4. Enums & State Machines
5. PNR Generation
6. Seat Allocation & Double-Booking Prevention
7. API Endpoints (planned)
8. Cross-Service Integration & Event Flow
9. Domain Services — Scope for V1
10. Shared Code (`skybook-common` additions)
11. Deferred / Out of Scope
12. Known Risks / Open Questions
13. Suggested Build Order
14. Implementation Notes (What Was Actually Built)
15. Files Added / Modified
16. Manual Testing Guide

---

# 1. Overview

Booking Service is the fourth backend module (after `auth-service`, `flight-service`, `notification-service`) and the first one built with the fuller layered architecture agreed in design review: `Controller → Facade → Service → Domain Services → Repository`, rather than the simpler three-layer approach used in `flight-service`. The reasoning: Booking is the first module that genuinely needs to orchestrate across service boundaries (Flight, eventually Inventory/Payment), so the extra layers earn their keep here in a way they wouldn't have for `flight-service`, which was entirely self-contained.

This document captures the finalized design from the Sprint 3 design review — entities, state machines, API surface, and cross-service integration approach — before any code is written. Two schema-shaping questions raised during review were resolved and are baked into this document:

- **Customer** is *not* a new entity — `Booking` references `customerId`, the existing `User` from `auth-service`.
- **Multi-passenger bookings** are supported from day one via a `BookingPassenger` join entity, and per-passenger fields (`travelClass`, `fareType`, `seatNumber`) live there rather than on `Booking`, avoiding a painful migration once mixed-class family bookings show up.

A few further refinements fell naturally out of those two decisions and are called out explicitly in **boxed notes** throughout — please confirm these before implementation starts.

---

# 2. Architecture — Layered Design

```
Controller
    │
    ▼
BookingFacade   ← the only layer that knows other services exist
    │
    ├── FlightServiceClient  (validate flight, e.g. GET /api/flights/{id})
    ├── BookingService       (owns the Booking aggregate; no knowledge of other services)
    │       │
    │       ▼
    │   Repository (BookingRepository, PassengerRepository, ...)
    │
    ├── Domain Services (called by Service, not stacked below it)
    │       ├── PnrGenerator
    │       ├── BookingStateMachine
    │       ├── BookingValidator
    │       ├── FareCalculator
    │       └── SeatAssignmentStrategy (interface only for now — see §9)
    │
    └── Kafka Producer → published only AFTER the DB transaction commits
            │
            ▼
      Notification Service (existing consumer pattern, see §8)
```

| Layer | Responsibility | Knows about other services? |
|---|---|---|
| **Controller** | HTTP binding only — request/response mapping, validation annotations, status codes. No business logic. | No |
| **BookingFacade** | Orchestrates the end-to-end use case: validate flight, check/reserve seat, call `BookingService` to persist, publish the Kafka event. This is the integration/anti-corruption layer. | **Yes — exclusively** |
| **BookingService** | CRUD + status-transition operations on the Booking aggregate. Owns the local transaction boundary. | No |
| **Domain Services** | Pure business logic, no I/O, no Spring dependencies beyond `@Component`. Trivially unit-testable without mocks. | No |
| **Repository** | Spring Data JPA, as in the other services. | No |

**Design principle carried over from review:** don't let Facade or Service become a thin pass-through layer with no added behavior — if a layer has nothing to orchestrate yet, it's a sign it isn't needed yet either.

---

# 3. Domain Model / Aggregate

Following DDD aggregate principles (to avoid `Booking` becoming a "god entity" as fields accumulate):

```
Booking (aggregate root)
    ├── BookingPassenger (1..N)   — one per traveler on this PNR
    ├── BookingContact   (1)      — who to notify; may not be a passenger
    ├── BookingPayment   (1)      — thin summary, not full payment detail
    └── BookingHistory   (0..N)   — append-only audit trail of status changes
```

Everything inside the aggregate is saved in one transaction via `Booking` as the root. Other aggregates (Flight, Customer/User) are referenced by id only, never by direct object navigation across service boundaries.

## 3.1 `Booking` (aggregate root)

| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| bookingReference | String | PNR, e.g. `SB8KF7` — unique, immutable, system-generated (§5) |
| customerId | Long | **Reference only** — the `id` of the `User` in `auth-service`. No local `Customer` table. |
| flightId | Long | Single flight per booking for v1 — see §11 (multi-flight itineraries deferred) |
| bookingStatus | BookingStatus | §4.1 |
| bookingDate | LocalDateTime | When the booking was created |
| totalFare | BigDecimal | **Derived** — sum of `BookingPassenger.fare` across all passengers, not independently settable |
| remarks | String | Free-text notes |
| createdAt / updatedAt / createdBy / updatedBy / version | — | Via shared `Auditable` (§10) |

## 3.2 `BookingPassenger` (new — enables multi-passenger PNRs)

| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| bookingId | Long (FK) | |
| passengerId | Long (FK) | See §3.4 — Passenger identity model |
| travelClass | TravelClass (enum) | **Moved from Booking** — different passengers on one PNR can fly different classes |
| fareType | FareType (enum) | **Moved from Booking** |
| seatNumber | String | **Moved from Booking** |
| fare | BigDecimal | Per-passenger fare; `Booking.totalFare` sums these |
| checkInStatus | CheckInStatus | **See boxed note below — moved here, not on Booking** |

> **📌 Refinement — CheckInStatus is per-passenger, not per-booking.** Once a PNR can hold multiple travelers, check-in is inherently something each traveler does individually (a parent can check in while a child's boarding pass is still pending, one passenger can no-show while others board). Putting `checkInStatus` on `Booking` would force one value to represent N independent facts. Moving it to `BookingPassenger` also gives `NO_SHOW` a natural home — it didn't have one in the three-way status split as originally drafted. This does mean the check-in/board endpoints move to a passenger sub-resource — see §7.

Unique constraint: `(flightId, seatNumber)` — moved here from the Booking-level idea originally discussed, since seat assignment is now per-passenger.

## 3.3 `BookingContact`

| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| bookingId | Long (FK) | |
| contactName | String | May differ from any passenger — e.g. a travel agent or parent booking for family |
| contactEmail | String | Used for the Kafka-driven notification flow (§8) |
| contactPhone | String | |

## 3.4 `BookingPayment` (thin summary — not the full payment domain)

| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| bookingId | Long (FK) | |
| paymentStatus | PaymentStatus | §4.2 |
| amount | BigDecimal | |
| currency | String | |
| externalPaymentReference | String | Opaque reference once Payment Service exists |
| paidAt | LocalDateTime | Nullable |

> **📌 Boundary note:** since Payment Service is explicitly future work, `BookingPayment` intentionally stays a summary/read-model, not a full transaction ledger. Building out a rich payment sub-entity now means unwinding it once Payment Service becomes the real source of truth.

## 3.5 `BookingHistory` (audit trail)

| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| bookingId | Long (FK) | |
| fieldChanged | String/enum | `BOOKING_STATUS` / `PAYMENT_STATUS` / `CHECK_IN_STATUS` |
| fromValue | String | |
| toValue | String | |
| changedAt | LocalDateTime | |
| changedBy | String | |
| reason | String | Nullable |

Populated automatically by `BookingStateMachine` whenever it performs a transition — not something `BookingService` has to remember to call separately. This is effectively a lightweight event-sourcing-lite audit log.

## 3.6 `Passenger` (traveler identity — separate from Customer)

Unchanged from the original proposal:

```
Passenger: id, title, firstName, middleName, lastName, dob, gender,
           nationality, passportNumber, passportExpiry, email, phone
```

`passportNumber`/`passportExpiry`/`dob` are sensitive PII — exclude from default logging/`toString()`, consider column-level encryption.

> **📌 Open question (not yet decided — see §12):** is a `Passenger` row created fresh every time someone is added to a booking, or reused/deduplicated across bookings for the same real traveler (e.g. by passport number, for a future "saved travelers" feature)? V1 assumption below is "create fresh each time" for simplicity — flag if that's wrong.

## 3.7 Customer — no local entity

`Booking.customerId` references `auth-service`'s `User.id` directly. No duplicated identity table in booking-service. If display data (name/email) is needed without a network call, denormalize a snapshot onto `BookingContact` rather than maintaining a second `Customer` table that can drift from `auth-service`.

---

# 4. Enums & State Machines

Three independent state machines, as agreed — but "independent" doesn't mean unconstrained; cross-machine invariants are enforced by `BookingValidator` (§9), not left implicit.

## 4.1 `BookingStatus` (on `Booking`)

| Value | Meaning |
|---|---|
| `CREATED` | Initial state, before payment/confirmation |
| `CONFIRMED` | Payment succeeded (or otherwise confirmed); booking is live |
| `CANCELLED` | Terminal |
| `COMPLETED` | Flight has flown and the booking lifecycle is closed out |

Valid transitions: `CREATED → CONFIRMED`, `CREATED → CANCELLED`, `CONFIRMED → CANCELLED`, `CONFIRMED → COMPLETED`.

## 4.2 `PaymentStatus` (on `BookingPayment`)

| Value | Meaning |
|---|---|
| `PENDING` | Awaiting payment |
| `PAID` | Payment captured |
| `FAILED` | Payment attempt failed — booking stays `CREATED`, can retry |
| `REFUNDED` | Only reachable after `BookingStatus = CANCELLED` |

Valid transitions: `PENDING → PAID`, `PENDING → FAILED`, `FAILED → PENDING` (retry), `PAID → REFUNDED`.

## 4.3 `CheckInStatus` (on `BookingPassenger` — per traveler)

| Value | Meaning |
|---|---|
| `NOT_OPEN` | Check-in window hasn't opened yet |
| `OPEN` | Check-in window open, passenger hasn't checked in |
| `CHECKED_IN` | Passenger has checked in |
| `BOARDED` | Passenger boarded the aircraft |
| `NO_SHOW` | Check-in window closed / flight departed without this passenger boarding |
| `CLOSED` | Terminal archival state once the flight's lifecycle ends |

Valid transitions: `NOT_OPEN → OPEN`, `OPEN → CHECKED_IN`, `CHECKED_IN → BOARDED`, `OPEN → NO_SHOW`, `CHECKED_IN → NO_SHOW`, and any state `→ CLOSED` once the flight has departed.

## 4.4 Cross-machine invariants (`BookingValidator`)

These are checked whenever any of the three statuses changes — not baked into any single enum:

- `CheckInStatus` may only advance past `NOT_OPEN` if `BookingStatus = CONFIRMED` and `PaymentStatus = PAID`.
- If `BookingStatus` becomes `CANCELLED`, every `BookingPassenger.checkInStatus` is force-transitioned to `CLOSED` (can't check in a cancelled booking).
- `PaymentStatus` may only become `REFUNDED` if `BookingStatus = CANCELLED`.

Real example this design specifically supports: `BookingStatus = CANCELLED`, `PaymentStatus = REFUNDED`, `CheckInStatus = CLOSED` — three independently-tracked facts that a single lifecycle enum couldn't have expressed cleanly.

---

# 5. PNR Generation

Format: `SB` + 4 random characters, drawn from a reduced alphabet that excludes visually ambiguous characters — `0`/`O`, `1`/`I`/`L` — since PNRs get read aloud and typed by agents and passengers.

Charset: `23456789ABCDEFGHJKMNPQRSTUVWXYZ` (32 characters → 32⁴ ≈ 1.05M combinations).

Examples: `SB8KF7`, `SBXQ4P`, `SBM7TR`.

`PnrGenerator` (domain service) generates a candidate and `BookingService` retries against the DB unique constraint on `bookingReference` on collision (same pattern conceptually as `scheduleCode` in flight-service, except random rather than sequential, so a retry loop is required rather than being inherently collision-free).

---

# 6. Seat Allocation & Double-Booking Prevention

- **v1 scope:** the passenger (or the client on their behalf) supplies a desired `seatNumber` on `BookingPassenger`. There is no automatic seat-map assignment yet — see `SeatAssignmentStrategy` in §9, which is deferred as an interface-only placeholder.
- **Defense in depth**, strongest to weakest:
  1. **Application-level pre-check** — query for an existing booking on `(flightId, seatNumber)` before insert, to fail fast with a clean `409`/`400` rather than a raw constraint violation.
  2. **DB unique constraint** on `(flightId, seatNumber)` at the `BookingPassenger` table — the real backstop against concurrent inserts racing each other (optimistic locking via `@Version` does *not* protect against this, since it only guards updates to a row that already exists, not two competing inserts).
  3. **Future: Inventory Service** as the authoritative seat map, with a proper reservation-hold mechanism.
- **Seat hold with TTL (flagged for later, not v1):** real airlines hold a seat for a few minutes during checkout and release it if payment doesn't complete in time. This implies a reaper job (same shape as `FlightGenerationJob`) sweeping expired `CREATED`/pre-payment bookings. Worth designing for now even if built later, since it affects how "reserved but not yet paid" state is represented.

---

# 7. API Endpoints (planned)

Base path: `/api/bookings`. Follows the same `/{id}/action` convention already established in `flight-service`.

| Method & Path | Purpose |
|---|---|
| `POST /api/bookings` | Create a booking (orchestrated by `BookingFacade`) |
| `GET /api/bookings/{id}` | Get by internal id |
| `GET /api/bookings/reference/{pnr}` | Get by PNR |
| `GET /api/bookings` | List all |
| `GET /api/bookings/search` | Filter by `bookingReference`, `flightId`, `passengerName`, `passportNumber`, `bookingStatus`, `paymentStatus`, `travelDate`, `bookingDate`, `email`, `phone` |
| `PATCH /api/bookings/{id}/confirm` | `CREATED → CONFIRMED` |
| `PATCH /api/bookings/{id}/cancel` | → `CANCELLED` (cascades `CheckInStatus → CLOSED` for all passengers) |
| `PATCH /api/bookings/{id}/complete` | `CONFIRMED → COMPLETED` |
| `PATCH /api/bookings/{id}/passengers/{passengerId}/check-in` | Per-passenger — see boxed note in §3.2 |
| `PATCH /api/bookings/{id}/passengers/{passengerId}/board` | Per-passenger |

> **📌 Consequence of the per-passenger CheckInStatus decision:** check-in/board move under a passenger sub-resource rather than being flat `PATCH /check-in` on the booking, since a single PATCH can no longer describe "check in the booking" once there can be multiple travelers. Confirm this shape before the controller is built.

---

# 8. Cross-Service Integration & Event Flow

- `BookingFacade` calls a `FlightServiceClient` (OpenFeign or `WebClient` against `flight-service`, e.g. `GET /api/flights/{flightId}`) to validate the flight exists and is bookable *before* creating the booking. This is the only place booking-service talks to another service directly.
- Once `Booking` + `BookingPassenger` + `BookingContact` are committed, a `BookingConfirmedEvent` is published to Kafka, consumed by `notification-service` — the same pattern already working for `EmailEvent` in `auth-service`. No synchronous REST call for notifications.
- **Dual-write caveat:** saving to Postgres and publishing to Kafka is two separate writes. If the Facade does "save, then publish" naively, a crash between the two either loses the notification or fires one for a booking that then fails to commit. For v1, wrap the publish in `@TransactionalEventListener(phase = AFTER_COMMIT)` so the event only fires once the transaction is actually durable — cheaper than a full transactional-outbox table, and enough for current scale. Revisit if guaranteed delivery becomes a real requirement.

---

# 9. Domain Services — Scope for V1

| Domain Service | V1 status | Notes |
|---|---|---|
| `PnrGenerator` | **Build now** | §5 |
| `BookingStateMachine` | **Build now** | `canTransition(entityType, from, to)` for all three enums; also writes `BookingHistory` |
| `BookingValidator` | **Build now** | Cross-machine invariants (§4.4) + input rules (e.g. `passportExpiry` must be after the flight's departure date) |
| `FareCalculator` | **Build now, pending a pricing data source decision** | Needs base-fare-by-route/class data from somewhere — a static config table, or a future Pricing/Inventory concern. Not blocking the rest of the module, but flagged so it isn't quietly hardcoded and forgotten. |
| `SeatAssignmentStrategy` | **Interface only — implementation deferred** | v1 only validates a user-supplied seat isn't taken (§6). A real assignment algorithm is premature before there's a seat map (Inventory Service). Defining an empty strategy now avoids an abstraction with nothing behind it. |

---

# 10. Shared Code (`skybook-common` additions)

Agreed to share, since these are cross-cutting technical concerns rather than one service's domain model:

- `Auditable` — currently lives inside `flight-service`; relocate to `skybook-common` so booking-service (and future services) don't redefine it.
- `BaseException` hierarchy + `ErrorResponse` shape — share the DTO/base class; keep each service's own `@RestControllerAdvice` (it's bound to that service's Spring context regardless).
- `KafkaTopics` — add a `BOOKING_EVENTS` constant.
- A new shared event payload, e.g. `BookingConfirmedEvent` (or a generic `BookingEvent` with a type discriminator, mirroring the existing `EmailEvent`/`EmailType` pattern).
- `Constants`, generic `Utilities` as originally proposed.

**Caution carried over from review:** domain DTOs and enums that represent *one service's* model — `BookingStatus`, `PaymentStatus`, `CheckInStatus`, request/response DTOs — should stay local to booking-service, not in common. The moment another service imports them directly, the two services can no longer evolve independently, which defeats much of the point of splitting them up. Kafka event payloads are the exception, since they're the intentional public contract between services.

---

# 11. Deferred / Out of Scope

Raised during design review but intentionally not built yet, to avoid complexity ahead of need:

- **Multi-flight / connecting itineraries / round trips** — `Booking.flightId` is a single flight for v1. Round-trip and multi-segment itineraries would need either multiple `Booking` rows linked by a trip id, or a `BookingSegment` concept — worth its own design pass once single-flight booking is solid end-to-end.
- **Automatic seat assignment** (`SeatAssignmentStrategy` real implementation) — see §9.
- **Full Payment Service integration** — `BookingPayment` stays a thin summary; real gateway integration is future work per the roadmap.
- **Transactional outbox** for Kafka publishing — using `@TransactionalEventListener(AFTER_COMMIT)` for v1 (§8); revisit if stronger delivery guarantees are needed.
- **Reusable/deduplicated Passenger profiles** across bookings (e.g. "saved travelers", loyalty linkage) — v1 creates a fresh `Passenger` row per booking; see the open question in §3.6.
- **Seat hold with TTL / reaper job** — designed for conceptually (§6) but not built in this pass.

---

# 12. Known Risks / Open Questions

- No authentication/authorization on these endpoints yet, consistent with `flight-service`'s current state — booking mutation endpoints should ultimately require an authenticated customer or `ADMIN` role.
- **`FareCalculator` needs a pricing data source before it can be implemented** — not specified yet (static table vs. config vs. future Pricing service).
- **Passenger identity model undecided** — create-fresh-per-booking (v1 assumption) vs. dedupe/reuse by passport number (§3.6). Low risk to defer, but changes the schema if reversed later.
- Dual-write mitigation is "after commit," not a full outbox — acceptable at current scale; revisit if event loss is ever observed in practice.
- No DB-level foreign keys beyond what Hibernate's mapping implies, consistent with the existing limitation noted in `FLIGHT_SCHEDULING_MODULE.md`.

---

# 13. Suggested Build Order

1. `skybook-common` additions: relocate `Auditable`, add `BookingEvent`/`KafkaTopics` entry.
2. Entities + enums + `BookingStateMachine`/`BookingValidator`/`PnrGenerator` (fully unit-testable domain layer, no Spring context needed for the tests).
3. Repositories (`BookingRepository`, `PassengerRepository`, `BookingPassengerRepository`, ...).
4. `BookingService` (owns the aggregate, no cross-service knowledge).
5. `FlightServiceClient` + `BookingFacade` (orchestration).
6. Controller + DTOs.
7. Kafka producer wiring (`AFTER_COMMIT` publish) + `notification-service` consumer for `BookingConfirmedEvent`.
8. Unit tests for every domain service and `BookingServiceImpl`, mirroring the depth of `FlightScheduleServiceImplTest`.
9. Update this document to "Implemented" status once built, matching the revision pattern used in `FLIGHT_SCHEDULING_MODULE.md`.

---

# 14. Implementation Notes (What Was Actually Built)

The design above was built essentially as specified, with a handful of concrete decisions made along the way:

| # | Decision | Why |
|---|---|---|
| 1 | `BookingFacade` only wraps `createBooking`, `confirmBooking`, `cancelBooking` - not every endpoint | These are the only operations with something to orchestrate (flight validation and/or a notification event). `getById`, `getByReference`, `getAll`, `search`, `complete`, `check-in`, `board` have no cross-cutting concern, so the controller calls `BookingService` directly for those - matches the "don't make Facade a pass-through" principle from section 2 rather than the simplified all-through-Facade arrow in the diagram. |
| 2 | `BookingFacade` methods are deliberately **not** `@Transactional` | `BookingService`'s individual methods are. By the time control returns to the Facade after calling a service method, that transaction has already committed - so publishing to Kafka afterwards achieves the same effect as `@TransactionalEventListener(phase = AFTER_COMMIT)` without the extra indirection. Revisit with a transactional outbox if stronger delivery guarantees are ever needed (still deferred, per section 11). |
| 3 | `FlightServiceClient` delegates to a declarative `FlightServiceFeignClient` (`spring-cloud-starter-openfeign`), not `RestClient` or `WebClient` | Originally built on Spring's blocking `RestClient` to avoid pulling in `spring-boot-starter-webflux` for one call. Switched to Feign on review since the Spring Cloud BOM is already imported at the root `backend/pom.xml`, and a declarative `@FeignClient` interface is less boilerplate than hand-rolling request building - `FlightServiceClient` now just translates `FeignException.NotFound`/`FeignException` into this module's own `FlightNotFoundForBookingException`/`FlightServiceUnavailableException`, same as before. `FlightServiceClientConfig` (the old `RestClient` bean) was deleted. |
| 3a | `spring-boot-starter-security` added, permit-all | Added on review for consistency with `auth-service`'s dependency set from day one, even though there's no JWT filter here yet. `SecurityConfig` disables CSRF and permits every request (`.anyRequest().permitAll()`) - mirrors `auth-service.SecurityConfig`'s structure minus the `JwtAuthenticationFilter`. TODO once auth-service tokens can be verified here. |
| 3b | `spring-boot-starter-actuator` added | `/actuator/health`, `/actuator/info`, `/actuator/metrics` exposed via `management.endpoints.web.exposure.include` in `application.yml`. Not wired into anything yet (no custom health indicators) - just standard ops visibility, same as any other Spring Boot service should have. |
| 3c | `mockito-junit-jupiter` and Testcontainers (`junit-jupiter`, `postgresql`, test scope) added explicitly to `pom.xml` | Mockito was already available transitively via `spring-boot-starter-test`; made explicit given how Mockito-heavy this module's tests are. Testcontainers isn't used by any test yet (all current tests are pure unit tests against mocks/real domain objects, no Spring context) - added now so a real DB-backed integration test can be added later without a pom change. Both are version-managed by the `spring-boot-dependencies` BOM already imported at the root, so no extra BOM import was needed. |
| 3d | `spring-boot-maven-plugin` given an explicit `<version>` in the root `backend/pom.xml`'s `<pluginManagement>` | Fixed a `build.plugins.plugin.version is missing` Maven warning across every module. The root aggregator only imports `spring-boot-dependencies`/`spring-cloud-dependencies` as BOMs in `dependencyManagement` - it does **not** extend `spring-boot-starter-parent` - so plugin versions (unlike dependency versions) were never centrally managed. Declared once in the parent instead of repeating a `<version>` in every child module's `pom.xml`. |
| 4 | `FlightDetails` (booking-service's local view of a flight) and a local `FlightBookingStatus` enum, not flight-service's actual DTO/enum | No compile dependency exists between the two modules, and per section 10, domain types stay local to the service that owns them even when values line up. `@JsonIgnoreProperties(ignoreUnknown = true)` lets this local subset deserialize flight-service's fuller response safely. |
| 5 | `CheckInStatus` transitions per docs section 4.3 - `checkInPassenger` auto-opens the window (`NOT_OPEN -> OPEN`) before checking in if needed | There's no separate scheduled trigger yet that opens check-in ahead of departure (that's deferred), so the check-in endpoint collapses both steps into one call, still recording both transitions in `BookingHistory`. |
| 6 | `confirmBooking` simulates a successful payment directly (`PaymentStatus -> PAID` then `BookingStatus -> CONFIRMED`) | Consistent with section 11 - there's no real Payment Service to call yet. This endpoint is the placeholder for where that integration will eventually plug in. |
| 7 | `BookingPassenger.passenger` cascades `PERSIST`/`MERGE` | Since v1 creates a fresh `Passenger` row per booking (the open question in section 3.6, exercised as-is for now), the whole aggregate - including new `Passenger` rows - saves in one `bookingRepository.save(booking)` call rather than needing a separate explicit passenger save. |
| 8 | `Booking.totalFare` / `BookingPayment.amount` default to a hardcoded `"USD"` currency | Not specified anywhere in the request shape yet - there's no multi-currency support today. Flagged here rather than silently baked in. |
| 9 | `BookingSearchRequest.travelDate` is accepted by the API but not actually filtered on | Doing so would require booking-service to look up each booking's flight via `FlightServiceClient` for every search result, which isn't wired up for the search path (only for create). Documented directly in the DTO's javadoc; `bookingDate` filtering works today. |
| 10 | A new shared `ErrorResponse` was added to `skybook-common`, but `flight-service`'s existing (identical-shape) copy was **not** migrated to it | Not worth the churn on already-tested code for a record with no behavior. New services should use the shared one. |
| 11 | `booking-service` uses its own Postgres database, `skybook_booking`, on port `8083` | Deliberately not repeating flight-service's known pre-existing misconfiguration (pointing at `skybook_auth`) - see `FLIGHT_SCHEDULING_MODULE.md` section 13. |
| 12 | No Flyway - `ddl-auto: update` | Matches flight-service's current approach for consistency; auth-service uses Flyway, flight-service doesn't. Booking-service follows the more recently built sibling module. |

Everything else in sections 1-13 was implemented as designed: three independent state machines with cross-machine invariants enforced by `BookingValidator`; `BookingStateMachine` recording every transition to `BookingHistory` via cascade rather than a separate save; the DDD aggregate shape (`Booking` root with `BookingPassenger`/`BookingContact`/`BookingPayment`/`BookingHistory`); the PNR format and charset exactly as specified; `SeatAssignmentStrategy` as an interface with only the manual/validate-only implementation; `FareCalculator` clearly flagged as a placeholder pricing model; and the Kafka event flow reusing the existing `EmailEvent`/`EmailService` plumbing in `notification-service` rather than a parallel notification pathway.

---

# 15. Files Added / Modified

**`skybook-common` (new/modified):**
```
entity/Auditable.java              - NEW, relocated from flight-service
exception/ErrorResponse.java       - NEW
event/BookingEvent.java            - NEW
event/BookingEventType.java        - NEW
event/EmailType.java               - MODIFIED: added BOOKING_NOTIFICATION
pom.xml                            - MODIFIED: added spring-boot-starter-data-jpa (for Auditable)
```

**`flight-service` (modified for the Auditable move only - no behavior change):**
```
entity/Flight.java                 - imports Auditable from skybook-common
entity/FlightSchedule.java         - imports Auditable from skybook-common
entity/Auditable.java              - DELETED (superseded by the shared one)
```

**`backend/pom.xml`** - added `<module>booking-service</module>`.

**`booking-service` (new module):**
```
pom.xml                            - dependencies: skybook-common, Spring Web, Validation, Data JPA,
                                      Security (permit-all), OpenFeign, Actuator, Kafka, PostgreSQL,
                                      SpringDoc, Lombok; test: Spring Boot Test, Kafka Test, Mockito
                                      (explicit), Testcontainers (junit-jupiter + postgresql, unused
                                      today - see section 14 #3c)
application.yml, application.properties - added management.endpoints.web.exposure.include
BookingServiceApplication.java     - @EnableFeignClients added
config/JpaAuditingConfig.java
config/KafkaProducerConfig.java
config/SecurityConfig.java         - NEW, permit-all placeholder (see section 14, #3a)
enums/{BookingStatus,PaymentStatus,CheckInStatus,TravelClass,FareType,BookingHistoryField}.java
entity/{Booking,BookingPassenger,BookingContact,BookingPayment,BookingHistory,Passenger}.java
domain/{PnrGenerator,BookingStateMachine,BookingValidator,FareCalculator,SeatAssignmentStrategy,ManualSeatAssignmentStrategy}.java
repository/{Booking,Passenger,BookingPassenger,BookingContact,BookingPayment,BookingHistory}Repository.java
dto/request/{PassengerBookingDetail,BookingContactRequest,CreateBookingRequest,CancelBookingRequest,BookingSearchRequest}.java
dto/response/{BookingPassengerResponse,BookingContactResponse,BookingPaymentResponse,BookingResponse}.java
mapper/{PassengerMapper,BookingMapper}.java
exception/{BookingNotFoundException,BookingPassengerNotFoundException,SeatAlreadyBookedException,
           FlightNotFoundForBookingException,FlightServiceUnavailableException,GlobalExceptionHandler}.java
client/{FlightBookingStatus,FlightDetails,FlightServiceClient,FlightServiceFeignClient}.java   - FlightServiceFeignClient is NEW (see section 14, #3); FlightServiceClientConfig.java (old RestClient bean) DELETED
service/BookingService.java, service/impl/BookingServiceImpl.java
facade/BookingFacade.java
producer/BookingEventProducer.java
controller/BookingController.java

Tests:
domain/{PnrGeneratorTest,FareCalculatorTest,BookingValidatorTest,BookingStateMachineTest}.java
service/impl/BookingServiceImplTest.java
```

**`notification-service` (new/modified):**
```
config/BookingKafkaConfig.java      - NEW, additive (doesn't touch the existing EmailEvent wiring)
consumer/BookingEventConsumer.java  - NEW, maps BookingEvent -> EmailEvent -> EmailService
application.yml                     - MODIFIED: added skybook.kafka.topics.booking-events
```

---

# 16. Manual Testing Guide

1. **Create a booking** - `POST /api/bookings` with `customerId`, `flightId`, one or more `passengers[]`, and `contact`. Confirm the response has a PNR matching `SB[A-Z2-9]{4}` (excluding `0/O/1/I/L`), `bookingStatus: "CREATED"`, `payment.paymentStatus: "PENDING"`, and `totalFare` equal to the sum of each passenger's fare.
2. **Seat collision** - create a second booking against the same `flightId` and `seatNumber` - expect `409` (`SeatAlreadyBookedException`).
3. **Expired passport** - submit a passenger whose `passportExpiry` is before the flight's departure date - expect `400`.
4. **Confirm** - `PATCH /api/bookings/{id}/confirm` - expect `bookingStatus: "CONFIRMED"`, `payment.paymentStatus: "PAID"`. Check notification-service logs (or your inbox, if mail credentials are configured) for the booking-confirmed email.
5. **Check-in then board** - `PATCH /api/bookings/{id}/passengers/{passengerId}/check-in`, then `.../board`. Confirm each passenger's `checkInStatus` progresses `NOT_OPEN -> CHECKED_IN -> BOARDED`. Attempting check-in before confirming payment should return `409`.
6. **Cancel** - `PATCH /api/bookings/{id}/cancel` on a confirmed, paid booking - expect `bookingStatus: "CANCELLED"`, `payment.paymentStatus: "REFUNDED"`, and every passenger's `checkInStatus` forced to `"CLOSED"`.
7. **Search** - `GET /api/bookings/search?passengerName=doe`, `?passportNumber=...`, `?bookingStatus=CONFIRMED`, etc. - confirm filters combine correctly.
8. **Flight validation failure** - create a booking against a non-existent `flightId` - expect `404` from `FlightNotFoundForBookingException` (requires flight-service running on the configured `flight-service.base-url`).
9. Swagger UI at `/swagger-ui.html` on port `8083` picks up `BookingController` automatically.
10. **Run the unit tests**: `mvn test -pl booking-service -am -Dtest=PnrGeneratorTest,FareCalculatorTest,BookingValidatorTest,BookingStateMachineTest,BookingServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false` (same pattern used for `flight-service` - see the note below).

> **Note on verification:** this environment doesn't have Maven/JDK 21 or a live Postgres/Kafka available, so none of the above has been run in this session - everything was written and manually traced against the design, the same way the initial `FlightScheduleServiceImplTest` suite was before you ran it locally. Please run `mvn test -pl booking-service -am` (and the manual testing steps above, once flight-service/Postgres/Kafka are up) and report back anything that fails.
