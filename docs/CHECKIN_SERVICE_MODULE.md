# 🛂 SkyBook Check-in Service Module — Design

---

## Project Information

| | |
|---|---|
| **Module** | `checkin-service` |
| **Branch** | `feature/checkin-management` |
| **Base package** | `com.skybook.praveen.checkinservice` |
| **Port** | `8087` (confirmed free — 8080 auth, 8082 flight, 8083 booking, 8084 inventory, 8085 notification, 8086 payment) |
| **Database** | `skybook_checkin` (PostgreSQL, `ddl-auto: update`) |
| **Status** | Implemented — full module (enums through controllers) built per §16's build order. 132 tests green: domain, service, facade, consumer, scheduler, and WebMvc layers (§17/§18). JPA/Testcontainers, full-stack Kafka integration, and concurrency tests not yet written — Docker/Testcontainers weren't reachable in the environment this was built in. |

Completes the airline lifecycle: `Search → Book → Reserve Seat → Pay → Confirm → **Check-in → Boarding Pass → Board** → Manifest`. Operational rather than transactional — this service is about *what happens at the airport*, not money or inventory truth.

**Load-bearing finding from this doc's research pass, not from the original brief:** booking-service already has a *minimal* check-in implementation (`BookingPassenger.checkInStatus`, `BookingStateMachine`'s `CHECK_IN_TRANSITIONS`, and `PATCH /api/bookings/{id}/passengers/{passengerId}/checkin`/`.../board`). Decision (confirmed): **checkin-service takes over fully.** See §11 for exactly what that migration entails.

---

# Table of Contents

1. [Overview](#1-overview)
2. [Architecture](#2-architecture)
3. [Domain Model / Aggregates](#3-domain-model--aggregates)
4. [Enums & State Machine](#4-enums--state-machine)
5. [Business Rules](#5-business-rules)
6. [Boarding Pass & QR / Token](#6-boarding-pass--qr--token)
7. [API Endpoints](#7-api-endpoints)
8. [Kafka — Events In and Out](#8-kafka--events-in-and-out)
9. [Cross-Service Integration](#9-cross-service-integration)
10. [Domain Services](#10-domain-services)
11. [Migration: Retiring booking-service's Check-in Fields](#11-migration-retiring-booking-services-check-in-fields)
12. [Shared Code (`skybook-common` additions)](#12-shared-code-skybook-common-additions)
13. [Configuration](#13-configuration)
14. [Deferred / Out of Scope](#14-deferred--out-of-scope)
15. [Known Risks / Open Questions](#15-known-risks--open-questions)
16. [Build Order](#16-build-order)
17. [Testing Plan](#17-testing-plan)
18. [Implementation Notes (What Was Actually Built)](#18-implementation-notes-what-was-actually-built)

---

# 1. Overview

`checkin-service` owns everything that happens between "booking is confirmed" and "aircraft door closes":

- Passenger check-in (opening/closing the window, marking passengers checked-in)
- Boarding pass generation (with a scannable, verifiable QR/token)
- Baggage check-in
- Seat changes (delegated to inventory-service for the actual hold/reserve)
- Boarding
- Gate status
- Flight manifest (who's checked in, who's boarded, who's a no-show)

What it deliberately does **not** own:

- Bookings, passengers-as-people, fares → booking-service (referenced by `bookingId`/`bookingPassengerId`/PNR only, same reference-only pattern payment-service uses for `bookingId`).
- Seat inventory truth (available/held/reserved counts) → inventory-service. checkin-service asks it "is this seat reserved for this booking" and, for seat changes, asks it to move the reservation; it never mutates inventory counts directly.
- Money → payment-service. Not consumed at all here (see §8 for why `PaymentEvent` is deliberately *not* wired in).
- Flight schedule truth (departure time, cancellation, gate systems in the aviation sense) → flight-service, consulted synchronously.

Architecture, layering, state-machine pattern, facade philosophy, test style: identical to flight/booking/inventory/payment. Anyone who has read `PAYMENT_SERVICE_MODULE.md` knows how this module works before reading further.

---

# 2. Architecture

```
controller → facade → service (impl) → repository
               │            │
               │            └── domain (state machine, validators, boarding-pass number
               │                        generator, token signer, baggage calculator)
               │
               ├── client    (FlightServiceClient → GET flight; InventoryServiceClient → GET/PATCH reservation)
               ├── producer  (CheckInEvent → skybook.checkin.events)
               ├── consumer  (BookingEvent ← skybook.booking.events)
               └── scheduler (no-show sweep, manifest finalization)
```

Same package layout as the brief specified — it already matches the fleet convention exactly (`config, controller, facade, service/impl, repository, domain, mapper, dto/{request,response}, entity, enums, producer, consumer, client, scheduler, util, exception`). No `specification/` package, same reasoning as payment-service (§2 of that doc): volumes don't justify JPA Specifications yet.

**The facade owns orchestration.** Concretely, check-in:

```
CheckInFacade.checkIn(checkInId)                 [NOT @Transactional]
  1. checkInService.validateForCheckIn(id)       [tx: window/status/document guards]
  2. inventoryClient.getReservation(...)         [external I/O — outside any DB transaction]
  3. checkInService.recordCheckIn(id)            [tx: transition + history, one commit]
  4. boardingPassService.generate(id)            [tx: BoardingPass row + number, one commit]
  5. producer.publishPassengerCheckedIn / BoardingPassGenerated  [after commit]
```

Same gateway-outside-transaction discipline payment-service established: synchronous calls to flight-service/inventory-service never happen inside a DB transaction.

---

# 3. Domain Model / Aggregates

```
CheckIn (root — one per passenger per flight, NOT per booking)
  ├── BoardingPass  (0..1 — created at CHECKED_IN, one per CheckIn)
  ├── Baggage       (0..N)
  └── CheckInHistory (1..N, append-only, cascade — same mechanism as Booking/Payment history)

FlightManifest (root — one per flight; passenger list is a live query over CheckIn
                until finalized, then a frozen snapshot — see §5.7)
```

`CheckIn` is per-passenger, not per-booking, mirroring why `BookingPassenger.checkInStatus` was already per-passenger, not per-`Booking` — two travelers on the same PNR can be in different check-in states (one checks in online, the other misses the window).

## 3.1 `CheckIn` (aggregate root)

| Field | Notes |
|---|---|
| `bookingId`, `bookingReference` | Reference-only, denormalized from `BookingEvent` at creation — same pattern as `Payment.bookingId`/`bookingReference`. |
| `bookingPassengerId` | The passenger's line-item id in booking-service — **not** a `Passenger.id**. Natural key for idempotent event replay (unique constraint, §5.8). |
| `flightId`, `flightNumber`, `originAirportCode`, `destinationAirportCode`, `departureTime` | Snapshotted from `BookingEvent`'s flight-context fields (already enriched there per the notification-service work). Snapshot, not live lookup — same trade-off `Payment.fareBreakdown` makes: check-in rules evaluate against what was booked, and a live flight-service call is still made at the moments that matter (window open, boarding) rather than trusted from the snapshot alone. |
| `passengerName`, `seatNumber`, `travelClass`, `fareType` | Snapshotted from `BookingEventPassenger`. |
| `status` | `CheckInStatus`, string-persisted (§4). |
| `documentVerified` | Boolean — set true when passport/document data was present on the booking event (§5.2's "cannot check in if passport/document data is missing" rule). |
| `checkedInAt`, `boardedAt` | Set on the respective transitions. |
| `gate` | Nullable — set at manifest/gate-assignment time (§7 adds an endpoint the original brief didn't list, since "Gate status" is a stated responsibility but no gate-setting API was specified). |
| `boardingGroup` | Nullable — simple v1 rule in `BoardingGroupAssigner` (§10), not configurable per airline yet. |

## 3.1.1 Aggregate invariants

| Invariant | Enforced by |
|---|---|
| One `CheckIn` per `bookingPassengerId` | **DB unique constraint** on `bookingPassengerId`. Makes the `BookingEvent CONFIRMED` consumer naturally idempotent (§8). |
| `CheckIn` cannot reach `CHECKED_IN` before its window opens or after it closes | Service layer (`CheckInValidator`), timestamps computed from the snapshotted `departureTime` and `checkin.window.*` config (§13). |
| At most one **`ACTIVE`** `BoardingPass` per `CheckIn` | **Service-layer guarantee** (revoke-old-then-insert-new on reissue, §5.6/§6) — not a plain DB unique constraint on `checkInId`, since a `CheckIn` legitimately accumulates multiple `REVOKED` rows over its lifetime (seat changes). `boardingPassNumber`/`token` are still DB-unique per row. |
| `boardingPassNumber` / QR token unique | **DB unique constraints**, same discipline as payment-service's reference columns. |
| Terminal states (`CANCELLED`, `NO_SHOW`, `COMPLETED`) are final | State machine — no outgoing transitions (§4). |
| `history` is append-only | Cascade-only write path, no update/delete — Booking/Payment precedent. |

## 3.2 `BoardingPass`

| Field | Notes |
|---|---|
| `boardingPassNumber` | Unique, system-generated — `BP-2026-K7M4Z9` style (§10, same generator pattern as `PaymentReferenceGenerator`/`PnrGenerator`). |
| `token` | The signed value encoded into the QR (§6). Unique. |
| `passengerName`, `bookingReference`, `flightNumber`, `originAirportCode`, `destinationAirportCode`, `seatNumber`, `gate`, `boardingTime`, `boardingGroup` | Denormalized onto the pass at generation time so it's a self-contained printable/scannable document — it must still be valid to display even if the live `CheckIn` row later changes state for unrelated reasons. |
| `status` | `ACTIVE`, `REVOKED` — revoked on seat change (reissue), cancellation, or no-show sweep. |
| `issuedAt`, `revokedAt` | |

## 3.3 `Baggage`

| Field | Notes |
|---|---|
| `checkIn` | ManyToOne — baggage belongs to a checked-in passenger (§5.5: only `CHECKED_IN` passengers can add baggage). |
| `tagNumber` | Unique, system-generated. |
| `weightKg` | `> 0`, validated. |
| `excess` | Boolean — computed against the allowance for `travelClass`/`fareType` (`BaggageAllowanceCalculator`, §10) at add-time; not recomputed retroactively if config changes later. |
| `excessCharge` | Nullable — populated when `excess` is true. v1: informational only, **not** wired to payment-service (that integration is out of scope, §14). |

## 3.4 `CheckInHistory`

Same mechanics as `BookingHistory`/`PaymentHistory`: `historyType` (string enum: `CHECKIN_OPENED, CHECKED_IN, BOARDED, NO_SHOW, CANCELLED, SEAT_CHANGED, BOARDING_PASS_REVOKED, ...`), `actor` (`USER, SYSTEM, KAFKA, SCHEDULER`), `source` (`API, BOOKING_EVENT, NO_SHOW_JOB, MANIFEST_JOB`), `correlationId`, `details`, `changedAt`.

## 3.5 `FlightManifest`

| Field | Notes |
|---|---|
| `flightId` | Unique — one manifest per flight. |
| `status` | `OPEN, FINALIZED`. |
| `finalizedAt` | Set once, by the finalization job or an explicit admin call (§5.7). |
| `checkedInCount`, `boardedCount`, `noShowCount`, `baggageCount`, `baggageWeightKg` | Denormalized counters, refreshed on finalize; `GET /api/manifests/{flightId}` computes them live via query when `OPEN`, reads the frozen row when `FINALIZED`. |

Deliberately **not** a parent of `CheckIn` — a manifest is a report over `CheckIn` rows for a flight, not a container that owns them. This avoids `CheckIn` needing a manifest FK before one exists (a flight has checked-in passengers well before anyone finalizes anything).

---

# 4. Enums & State Machine

All enum fields `@Enumerated(EnumType.STRING)` — standing rule.

## 4.1 `CheckInStatus`

**Resolved (was §15.2 open question):** `NO_SHOW` means *didn't fly* — reachable from any pre-boarding state, not only `CHECKED_IN`. This matches the real-world meaning of "no-show" (a confirmed passenger who never boards, whether or not they checked in) rather than the brief's literal transition list, which only wired `CHECKED_IN → NO_SHOW` and left an orphaned `OPEN → CLOSED` transition with no stated terminal status. No separate `CLOSED` state — "checked in but vanished" vs. "never checked in at all" is answerable from `CheckInHistory`/`checkedInAt` on the row, not a distinct enum value. Also broadened: `CANCELLED` is reachable from any non-terminal state, not only `CHECKED_IN` — a booking can be cancelled before its check-in window ever opens, and that must still resolve to `CANCELLED`, not sit unresolved.

| From | Allowed to |
|---|---|
| `NOT_OPEN` | `OPEN`, `NO_SHOW`, `CANCELLED` |
| `OPEN` | `CHECKED_IN`, `NO_SHOW`, `CANCELLED` |
| `CHECKED_IN` | `BOARDED`, `NO_SHOW`, `CANCELLED` |
| `BOARDED` | `COMPLETED` |
| `NO_SHOW`, `CANCELLED`, `COMPLETED` | — terminal |

`CheckInStateMachine`: identical shape to `PaymentStateMachine` — `Map<CheckInStatus, Set<CheckInStatus>>` backed by `EnumMap`, populated in a static block with `EnumSet.of(...)`, `canTransition`/`transition` pair, illegal moves throw `IllegalStateException` → mapped to **409** by `GlobalExceptionHandler` (same convention as every other service), `recordHistory` appends a `CheckInHistory` row in-memory for cascade persistence.

## 4.2 `BoardingPassStatus`

`ACTIVE, REVOKED`. Plain vocabulary, no machine — a boarding pass is issued once per `CheckIn` and revoked at most once (reissue creates a new pass + revokes the old, §6).

## 4.3 `ManifestStatus`

`OPEN, FINALIZED`. `FINALIZED` is terminal (§5.7 — "finalized manifest is read-only").

## 4.4 `CheckInHistoryType`

`CHECKIN_OPENED, CHECKED_IN, BOARDED, NO_SHOW, CANCELLED, SEAT_CHANGED, BAGGAGE_ADDED, BOARDING_PASS_ISSUED, BOARDING_PASS_REVOKED`

---

# 5. Business Rules

Reproducing the brief's rules 1-7, organized by where each is enforced (payment-service's §3.1.1 discipline: a DB-enforced rule survives buggy code, a code-enforced rule does not).

## 5.1 Check-in opening

| Rule | Enforced by |
|---|---|
| Only `CONFIRMED` bookings get a `CheckIn` row at all | Consumer only creates rows on `BookingEvent{type=CONFIRMED}` (§8) — there is structurally no `CheckIn` for a non-confirmed booking. |
| Seat must be `RESERVED` in inventory | `CheckInValidator`, verified via `GET /api/reservations/booking/{bookingId}` on inventory-service at window-open and at check-in time (not trusted from the snapshot alone). |
| Flight must not be `CANCELLED`/closed | `CheckInValidator`, verified via a synchronous `FlightServiceClient.getFlight(flightId)` call (flight-service has no Kafka producer yet — see §9.2 for why this is synchronous, not event-driven, in v1). |
| Window: opens `checkin.window.opens-hours-before-departure` (default 24h) before departure, closes `checkin.window.closes-minutes-before-departure` (default 45m) before | `CheckInValidator`, computed from the snapshotted `departureTime`. The no-show sweep scheduler (§10) is what actually drives `NOT_OPEN/OPEN → NO_SHOW` at the close boundary — nothing else polls for it. |

## 5.2 Passenger check-in

| Rule | Enforced by |
|---|---|
| Only booked passengers with a confirmed booking check in | Structural — a `CheckIn` row only exists for a `CONFIRMED` booking's passengers. |
| Passenger must have a reserved seat | Same inventory check as §5.1. |
| Cannot check in twice | State machine — `CHECKED_IN` has no self-loop. |
| Cannot check in after window closes | `CheckInValidator`, same window math as §5.1; also structurally blocked once the sweep has moved the row to `NO_SHOW` (terminal). |
| Cannot check in if passport/document data is missing | `documentVerified` flag (§3.1), computed at `CheckIn` creation from whether the snapshotted passenger data included document fields. v1 does **not** validate document authenticity — presence only (§14). |
| Cannot check in if booking is cancelled/refunded | Consumer transitions the row to `CANCELLED` on `BookingEvent{type=CANCELLED}` (§8); the state machine then structurally blocks `CANCELLED → CHECKED_IN`. |

## 5.3 Boarding pass

| Rule | Enforced by |
|---|---|
| Generated only after successful check-in | Facade step order (§2) — `boardingPassService.generate` only runs after `recordCheckIn` commits. |
| One active pass per `CheckIn` | **DB unique constraint** on `(checkInId, status=ACTIVE)` conceptually — implemented as: revoke-then-insert on reissue, never two `ACTIVE` rows (service-layer guarantee backed by the unique constraint on `checkInId` for the common case). |
| Unique `boardingPassNumber` / token | **DB unique constraints**. |
| Cancelled/no-show passengers cannot use the pass | Gate verification (§6) checks live `CheckIn.status`, not just pass existence — a revoked/stale pass fails verification even if someone still has the QR image. |
| Contains bookingRef, passenger, flight, seat, gate, boarding time | §3.2 fields. |

## 5.4 Boarding

| Rule | Enforced by |
|---|---|
| Only after `CHECKED_IN` | State machine (`CHECKED_IN → BOARDED` only). |
| Cannot board twice | State machine — `BOARDED` has no self-loop. |
| Cannot board before boarding opens / after gate closes | `CheckInValidator`, config-driven offsets from `departureTime` (`checkin.boarding.opens-minutes-before-departure`, `checkin.boarding.gate-closes-minutes-before-departure` — **new config this doc adds**; the brief states the rule but not the offsets). |
| Cannot board if flight cancelled | Same synchronous flight-service check as §5.1. |
| Cannot board if pass invalid/revoked | `BoardingPassStatus != ACTIVE` blocks at the facade before the state machine is even consulted. |

## 5.5 Baggage

| Rule | Enforced by |
|---|---|
| Only for `CHECKED_IN` passengers | Service guard — `Baggage.checkIn.status != CHECKED_IN` rejected with 409. |
| Weight `> 0` | Bean validation on the request DTO. |
| Allowance depends on `travelClass`/`fareType` | `BaggageAllowanceCalculator` (pure domain, mirrors `RefundCalculator`'s per-fare-line shape) against `checkin.baggage.allowance-kg.*` config (§13). |
| Excess marked/charged | `Baggage.excess`/`excessCharge`, computed at add-time (§3.3). |
| Unique tag number | **DB unique constraint**. |
| Cancelled/no-show baggage rejected | Same `CheckIn.status` guard as the first rule. |

## 5.6 Seat change

| Rule | Enforced by |
|---|---|
| Only before boarding | State machine — the endpoint only accepts `CheckIn.status` in `{OPEN, CHECKED_IN}`. |
| New seat available/reserved via inventory-service | Facade calls inventory-service to move the reservation (release old + reserve new — same two-call, compensate-on-failure shape `BookingFacade.holdSeatsOrCompensate` already uses); checkin-service never mutates inventory counts itself. |
| Not allowed after `BOARDED` / after gate closes | Same guards as boarding (§5.4). |
| Updates the boarding pass | If a `BoardingPass` already exists (post-check-in seat change), it's revoked and reissued with the new seat (§6) rather than mutated in place — a boarding pass is a point-in-time document, not a live view. |

## 5.7 Manifest

| Rule | Enforced by |
|---|---|
| Includes all checked-in passengers, excludes cancelled | Query filters `CheckIn.status NOT IN (CANCELLED)` while `OPEN`. |
| Marks `NO_SHOW` after gate close | The no-show sweep scheduler (§10) — any `NOT_OPEN`/`OPEN`/`CHECKED_IN` row that never reached `BOARDED` by gate-close time moves to `NO_SHOW` (§4.1's resolved semantics: didn't fly, whether or not they checked in). |
| Includes baggage count/weight | Denormalized counters (§3.5), refreshed on finalize. |
| Finalized only after gate close or departure | `POST /api/manifests/{flightId}/finalize` (§7) rejects if called before the configured gate-close offset has passed for that flight. |
| Finalized manifest is read-only | `ManifestStatus.FINALIZED` is terminal; the finalize endpoint and the sweep job both no-op against an already-finalized manifest (idempotent). |

---

# 6. Boarding Pass & QR / Token

The brief calls for "a signed token or encoded boarding information," scannable for gate verification. Design (new — the brief didn't specify the mechanism):

- **Token** = `Base64URL(payload) + "." + Base64URL(HMAC-SHA256(payload, checkin.boarding-pass.signing-key))`, where `payload` is a compact pipe-delimited string: `boardingPassNumber|bookingReference|flightId|seatNumber|checkInId`. Same spirit as a JWT but hand-rolled and minimal — no need for the claims/header machinery a full JWT library brings for a single-purpose internal token.
- **QR image**: reuses the same `com.google.zxing` dependency notification-service already uses for the email QR (`QrCodeGenerator` there is a candidate to promote into `skybook-common` if a second service needs byte-identical QR generation — flagged, not decided, in §15).
- **Verification** (`GET /api/boarding-passes/verify?token=...` — **added**, not in the original endpoint list, but required for "scannable for verification at the gate" to mean anything): recomputes the HMAC over the decoded payload, rejects on mismatch (tampered/forged), then loads the `CheckIn` by `checkInId` from the payload and checks `status == BOARDED` is *not* yet true (prevents re-scanning a boarded pass) and `BoardingPass.status == ACTIVE`. Returns pass-or-fail plus the passenger/seat/gate info a gate reader would display.
- **Signing key**: a single symmetric key from config (`checkin.boarding-pass.signing-key`) for v1 — key rotation and per-flight/per-day keys are out of scope (§14).

---

# 7. API Endpoints

## Check-ins — `/api/checkins`

| Method | Path | Notes |
|---|---|---|
| POST | `/api/checkins` | Manual/direct creation — the `BookingEvent CONFIRMED` consumer covers the normal path (§8). |
| GET | `/api/checkins/{id}` | 404 if unknown. |
| GET | `/api/checkins/booking/{bookingId}` | All passengers on a PNR. |
| GET | `/api/checkins/flight/{flightId}` | Feeds the manifest view. |
| PATCH | `/api/checkins/{id}/open` | **Added** — explicit window-open trigger (idempotent; also happens implicitly at first legal check-in attempt if the window has opened but nothing has explicitly opened it yet — same "implicit-open" pattern booking-service's stopgap `checkInPassenger` already uses). |
| PATCH | `/api/checkins/{id}/checkin` | `OPEN → CHECKED_IN`. Triggers boarding pass generation. |
| PATCH | `/api/checkins/{id}/board` | `CHECKED_IN → BOARDED`. |
| PATCH | `/api/checkins/{id}/seat` | Seat change (§5.6). Body: new seat number. |
| PATCH | `/api/checkins/{id}/gate` | **Added** — gate assignment; the brief lists "Gate status" as an owned responsibility but specifies no endpoint. |

## Baggage — `/api/baggage`

| Method | Path |
|---|---|
| POST | `/api/baggage` |
| GET | `/api/baggage/checkin/{checkInId}` |

## Boarding Passes — `/api/boarding-passes`

| Method | Path | Notes |
|---|---|---|
| GET | `/api/boarding-passes/{id}` | |
| GET | `/api/boarding-passes/verify` | **Added** — §6. Query param `token`. |

## Manifests — `/api/manifests`

| Method | Path | Notes |
|---|---|---|
| GET | `/api/manifests/{flightId}` | Live view while `OPEN`, frozen snapshot once `FINALIZED`. |
| POST | `/api/manifests/{flightId}/finalize` | **Added** — §5.7. |

Error contract identical to the rest of the fleet: 404/409/400, optimistic-lock 409, plus **422** for boarding-pass verification failures (tampered/expired/already-boarded token) — same "request was valid, the real-world check failed" distinction payment-service uses for gateway declines.

---

# 8. Kafka — Events In and Out

## Consumed — `skybook.booking.events` only

| Event type | Action |
|---|---|
| `CONFIRMED` | Create one `CheckIn` (status `NOT_OPEN`) per passenger, snapshotting flight context + passenger fields already carried on `BookingEvent` (§3.1). Idempotent by `bookingPassengerId` (unique column) — a redelivered event is a no-op. |
| `CANCELLED` | Transition every non-terminal `CheckIn` for that booking to `CANCELLED`; revoke any active `BoardingPass`. |
| `CREATED`, `EXPIRED`, `COMPLETED` | Ignored (logged) — `COMPLETED` in particular is booking-service's own post-flight bookkeeping, not a trigger for anything here. |

**Deliberately not consuming `PaymentEvent`.** The brief lists it, but by the time `BookingEvent{CONFIRMED}` exists at all, payment has already succeeded — that's the settled Sprint 6 causality chain (`PaymentSucceeded → booking-service confirms → BookingEvent CONFIRMED`, documented in `PAYMENT_SERVICE_MODULE.md` §11). Consuming both would mean two independent signals for the same fact with no clear ordering guarantee between them; `BookingEvent{CONFIRMED}` alone is both necessary and sufficient.

**Deliberately not consuming a `FlightEvent`.** No such event exists — flight-service has no Kafka producer at all today (confirmed: zero `KafkaTemplate` usage in that module). `skybook.flight.events` is reserved in `KafkaTopics` but unused. Flight-cancellation awareness in v1 comes from the synchronous `FlightServiceClient` check at the moments it matters (window-open, check-in, boarding — §5.1/§5.4), not from an event. Building `FlightCancelledEvent`/`FlightUpdatedEvent` in flight-service is a real prerequisite for a *fully* event-driven design, but it's cross-cutting (useful to booking-service too) and shouldn't block checkin-service v1 — flagged in §14/§15.

## Produced — `skybook.checkin.events` (topic already reserved in `KafkaTopics`)

New shared contract `CheckInEvent` with `CheckInEventType`: `PASSENGER_CHECKED_IN, BOARDING_PASS_GENERATED, PASSENGER_BOARDED, PASSENGER_NO_SHOW, PASSENGER_CHECKIN_CANCELLED`. Fields (mirroring `PaymentEvent`'s shape): `type`, `checkInId`, `bookingId`, `bookingReference`, `bookingPassengerId`, `passengerName`, `flightId`, `flightNumber`, `seatNumber`, `boardingPassNumber` (checked-in/boarding-pass events), `gate`, `occurredAt`.

**Consumer:** booking-service, to keep `BookingPassenger.checkInStatus` as a denormalized read-model (§11) — the same "another service snapshots my event onto its own row" pattern `BookingEvent.paymentStatus` already uses in reverse.

Published by the facade after commit — standard after-commit rationale, same as every other service.

---

# 9. Cross-Service Integration

## 9.1 inventory-service (synchronous, read + write)

- `GET /api/reservations/booking/{bookingId}` — confirm each passenger's seat is `RESERVED` before allowing check-in (§5.1/§5.2). Real endpoint, already exists (`SeatReservationController`).
- Seat change (§5.6): release old seat's reservation + reserve the new one — two calls via the same Feign-client-wrapped pattern `InventoryServiceClient` already establishes in booking-service (`Optional<T>` returns, `*Quietly` variants for compensating cleanup, domain exceptions on failure).

## 9.2 flight-service (synchronous, read-only)

- `GET /api/flights/{id}` — status + departure time, at window-open and at the boarding/check-in moments that matter (§5.1/§5.4). checkin-service gets its own local `FlightCheckInStatus`-style enum copy of the subset of values it cares about (`SCHEDULED, CANCELLED, DELAYED, BOARDING, DEPARTED`) — same "don't import another service's enum" discipline booking-service's `FlightBookingStatus` already establishes.
- No write access — checkin-service never calls flight-service's `PATCH` status endpoints (e.g. it does **not** flip a flight to `BOARDING` itself, even though that status value exists on flight-service's side) — that would blur ownership; flagged as a possible future integration, not built now (§14).

## 9.3 booking-service (asynchronous, one-directional in each event type)

- Consumes `BookingEvent` (in, §8).
- Produces `CheckInEvent`, consumed by booking-service to update its denormalized `checkInStatus` (out, §8, detailed in §11).
- No synchronous calls in either direction.

## 9.4 payment-service

No integration. Not consumed, not called. Baggage excess charges are informational only in v1 (§5.5, §14) — wiring them into a real payment/invoice is future work.

---

# 10. Domain Services

| Class | Role |
|---|---|
| `CheckInStateMachine` | §4.1. |
| `CheckInValidator` | Guard clauses: window open/close math, document presence, cancelled-booking block, gate-close cutoffs. |
| `BoardingPassNumberGenerator` | §3.2 — `BP-{year}-{6 chars}`, same alphabet/collision-retry-via-caller pattern as `PaymentReferenceGenerator`/`PnrGenerator` (no in-generator retry loop; DB unique constraint + caller retry). |
| `BoardingPassTokenSigner` | §6 — HMAC-SHA256 sign/verify. |
| `BaggageAllowanceCalculator` | §5.5 — pure domain, per `(travelClass, fareType)` allowance lookup + excess computation, mirrors `RefundCalculator`'s per-line shape. |
| `BoardingGroupAssigner` | v1: simple rule (e.g. by `travelClass`, PREMIUM/FLEXI boards first) — not configurable per airline yet (§14). |

---

# 11. Migration: Retiring booking-service's Check-in Fields

Concrete follow-on work in **booking-service**, not part of checkin-service's own branch/PR, but required for the "takes over fully" boundary decision to actually hold:

1. **Add `CheckInEventConsumer`** to booking-service, consuming `skybook.checkin.events` and writing `BookingPassenger.checkInStatus` from it — booking-service's copy becomes a read-model, not a source of truth.
2. **Deprecate, then remove** `BookingService.checkInPassenger`/`boardPassenger` and their controller endpoints (`PATCH /api/bookings/{id}/passengers/{passengerId}/checkin`, `.../board`). Deprecate-first, remove-later (not simultaneous with checkin-service's launch) — a live cutover on two services at once is exactly the kind of thing that produces a silent split-brain window; a deprecation period lets checkin-service prove itself against real traffic first.
3. **`BookingStateMachine.CHECK_IN_TRANSITIONS`** (and the `CheckInStatus` enum it references) can stay as-is *if* step 1 only ever writes through `bookingStateMachine.transitionCheckInStatus(...)` rather than setting the field directly — reuses the existing validated transition table instead of duplicating it, and keeps `BookingHistory` getting entries for checkin-service-driven changes too (useful for a single "what happened to this booking" audit trail in one place, even after ownership of the *decision* moves to checkin-service).
4. **`BookingStateMachine`'s existing cancellation cascade** (cancelling a booking already pushes every passenger's `checkInStatus` to `CLOSED` today) becomes redundant with checkin-service's own `CANCELLED` handling once step 1 lands — leave it in place regardless; it's harmless idempotent belt-and-braces on the read-model, not a correctness risk.

This section exists so the "aggregate boundary" decision from this doc's intro is traceable to concrete action items, not just a paragraph.

---

# 12. Shared Code (`skybook-common` additions)

- `event/CheckInEvent`, `event/CheckInEventType` — mirrors `PaymentEvent`/`BookingEvent` style. Additive only.
- `KafkaTopics.CHECKIN_EVENTS` — **already exists** (`"skybook.checkin.events"`), reused as-is, no change needed.
- Nothing else — `Auditable`, `ErrorResponse` reused as-is (same fields every other service already relies on: `createdAt/updatedAt/createdBy/updatedBy/version` on `Auditable`; `timestamp/status/error/message/path` on `ErrorResponse`).

---

# 13. Configuration

```yaml
server.port: 8087
spring.datasource.url: jdbc:postgresql://localhost:5432/skybook_checkin
spring.kafka.bootstrap-servers: localhost:9092   # consumer group: checkin-service

flight-service.base-url: http://localhost:8082
inventory-service.base-url: http://localhost:8084

checkin:
  window:
    opens-hours-before-departure: 24
    closes-minutes-before-departure: 45
  boarding:
    opens-minutes-before-departure: 45     # new — opens when check-in closes, in v1
    gate-closes-minutes-before-departure: 20
  baggage:
    allowance-kg:
      economy-saver: 15
      economy-flexi: 20
      premium-economy: 25
      business: 32
  boarding-pass:
    signing-key: ${CHECKIN_BOARDING_PASS_KEY}   # symmetric HMAC key, §6
  sweep:
    no-show-interval-ms: 60000        # mirrors inventory-service's hold-expiry job cadence
    manifest-finalize-interval-ms: 60000
```

Config classes mirror the fleet: `JpaAuditingConfig`, `KafkaProducerConfig` (`CheckInEvent`) + consumer factory for `BookingEvent`, `SecurityConfig` permit-all placeholder, `FeignConfig`/client registration for flight-service and inventory-service (same shape as booking-service's).

**Create the database before first run:** `CREATE DATABASE skybook_checkin;`

---

# 14. Deferred / Out of Scope

- **flight-service Kafka producer / `FlightCancelledEvent`/`FlightUpdatedEvent`** — real prerequisite for event-driven flight-cancellation awareness; v1 uses synchronous polling at the moments that matter instead (§9.2). Cross-cutting (booking-service would benefit too), so it's flagged here rather than silently built as a checkin-service side-quest.
- **Real document/passport verification** — v1 only checks presence, not authenticity (§5.2).
- **Real gate-management / airport-systems integration** — `gate`/`boardingTime` are plain fields set via API, not synced with any real airport system.
- **Baggage-excess-charge → payment-service wiring** — informational only in v1 (§5.5, §9.4).
- **Boarding-pass signing key rotation** — single static key from config (§6).
- **Configurable boarding-group rules per airline/fare-matrix** — `BoardingGroupAssigner` is a simple v1 rule (§10).
- **Multi-leg / connecting flights** — one `CheckIn` per passenger per flight assumes single-segment bookings, matching booking-service's own current model (a `Booking` references one `flightId`).
- **Promoting `QrCodeGenerator` to `skybook-common`** — notification-service and checkin-service would then generate byte-identical QR PNGs from one class; noted as a possible dedup, not decided (§6).
- **Transactional outbox** — same standing deferral as every other service.

---

# 15. Known Risks / Open Questions

1. **Migration window split-brain (§11).** Between checkin-service's launch and booking-service's endpoint removal, both `PATCH /api/bookings/.../checkin` and checkin-service's own check-in endpoint could theoretically be called for the same passenger. Mitigation: deprecate booking-service's endpoints immediately (return a `Deprecation` header / log a warning) even before removing them, and point any client at checkin-service from day one — the deprecation period is about not breaking existing callers instantly, not about leaving the door open.
2. ~~No-show semantics~~ — **resolved**, see §4.1/§5.7: `NO_SHOW` now covers both "checked in, never boarded" and "never checked in at all"; no separate `CLOSED` state.
3. **Boarding-pass reissue on seat change** creates a `REVOKED` pass with no forwarding pointer to its replacement in the schema as drafted (§3.2/§5.6) — worth adding a `reissuedAsId` self-reference if support needs to trace "what did this old QR turn into" during implementation.
4. **Snapshot staleness.** `CheckIn`'s flight-context snapshot (§3.1) can drift from live flight-service data (delay, gate reassignment mid-window) since it's only refreshed by explicit calls at specific moments, not continuously. Same trade-off payment-service accepted for fare snapshots (§16.1 of that doc) — accepted here too, for the same reason (avoids a live dependency for every read).
5. **Sequence-backed boarding-pass numbers under Testcontainers tests** — same caution payment-service's implementation notes flagged for invoice numbers: don't assert absolute generated values in JPA tests.

---

# 16. Build Order

Same discipline as every prior service (compile between steps, commit on green):

1. Module scaffold (pom, app class, yml, parent registration) — port 8087
2. Enums (`CheckInStatus`, `BoardingPassStatus`, `ManifestStatus`, `CheckInHistoryType`, local `FlightCheckInStatus` copy)
3. Entities: `CheckIn` → `BoardingPass` → `Baggage` → `FlightManifest` → `CheckInHistory`
4. Repositories
5. Response DTOs + mappers, then request DTOs
6. Domain: state machine → validators → boarding-pass number generator → token signer → baggage calculator → boarding-group assigner
7. Exceptions + handler (incl. the 422 verification-failure mapping)
8. `FlightServiceClient` + `InventoryServiceClient` (Feign, mirroring booking-service's client package shape)
9. Services: `CheckInService` → `BoardingPassService` → `BaggageService` → `ManifestService`
10. Facade + producer
11. `BookingEventConsumer`
12. Scheduler: no-show sweep, manifest finalization
13. Controllers (4)
14. Config
15. Tests per §17, then docs/report/Postman updates
16. **Companion booking-service PR** per §11 (separate branch)

# 17. Testing Plan

Target ~180-220 tests, matching payment-service's pyramid shape and the brief's explicit §10 testing rules:

| Layer | Coverage | Status |
|---|---|---|
| Domain unit | State machine golden transition table (incl. the broadened `NO_SHOW`/`CANCELLED` rules, §4.1); `CheckInValidator` window/document/status gates; `BaggageAllowanceCalculator` per class/fare-type incl. excess; `BoardingPassNumberGenerator`/`BaggageTagGenerator`/`BoardingPassTokenSigner` (valid sign→verify round trip, tampered-signature/tampered-payload/malformed-token/wrong-key rejection); `BoardingGroupAssigner`. | ✅ Done — 46 tests |
| Service (`CheckInServiceImpl`/`BoardingPassServiceImpl`/`BaggageServiceImpl`/`ManifestServiceImpl`) | Creation idempotency, implicit window-open, window/document guards, duplicate check-in/pre-check-in-boarding rejection, seat-change status gate, cancellation cascade (non-terminal rows only), no-show sweep; boarding-pass generate/reissue/revoke and the full `verify()` failure ladder (malformed → unknown → revoked → already-boarded → success); baggage allowance/excess; manifest live view, gate-close cutoff, idempotent finalize, zero-`CheckIn` immediate-finalize, `finalizeDueManifests` skip-already-finalized. | ✅ Done — 38 tests |
| Facade (`CheckInFacadeTest`) | Flight-cancelled/seat-unavailable rejection before any DB write; permissive empty-reservations-list path; seat-change's reserve-before-write-then-release-after ordering and compensate-on-failure behavior; cancellation/no-show fan-out (revoke pass + publish event per affected row). | ✅ Done — 12 tests |
| Consumer (`BookingEventConsumerTest`) | One `CheckIn` per passenger built correctly from event+passenger fields; pre-enrichment events (missing `bookingId`/`passengers`/`bookingPassengerId`) skipped loudly, not thrown; malformed `departureTime` parses as null; `CANCELLED` delegates to the facade; other event types ignored. | ✅ Done — 8 tests |
| Scheduler (`NoShowSweepJob`/`ManifestFinalizationJob`) | Thin delegation checks (delegates to the facade/service method; a zero-affected-rows return is a silent no-op) — the actual sweep/finalize logic lives in and is covered by the already-tested facade/service methods. | ✅ Done — 4 tests |
| WebMvc | All 4 controllers (`CheckInController`, `BoardingPassController`, `BaggageController`, `FlightManifestController`); 201/200/404/409/400/**422** contract per endpoint. | ✅ Done — 24 tests |
| JPA — Testcontainers PostgreSQL | Every §3.1.1 DB-enforced invariant: one `CheckIn` per `bookingPassengerId`, unique `boardingPassNumber`/token/baggage tag, append-only history. | ❌ Not written — needs Docker (unreachable in this environment: `docker ps` failed to connect to the daemon) |
| Integration | Full-stack: `BookingEvent CONFIRMED` off a real broker → check-in → boarding pass → board → manifest finalize, `CheckInEvent`s consumed off real Kafka. | ❌ Not written — needs Docker |
| Concurrency | Duplicate concurrent check-in race; concurrent seat-change race; no-show sweep firing concurrently with a legitimate late check-in at the exact boundary. | ❌ Not written |

**132 of the targeted ~180-220 tests exist and are green** (§18 has the running total and what each layer found). An earlier draft of this table marked the scheduler row "not written — by design," reasoning the jobs had nothing scheduler-specific to verify; that turned out to be wrong by the fleet's own precedent (`inventory-service`'s `SeatHoldExpiryJobTest` exists for exactly this shape of class), so it was written after all. The three still-unchecked rows are exactly the layers that need a real Postgres/Kafka broker (Testcontainers) rather than plain Mockito/`MockMvc` — picking those up is the natural next session, once Docker is reachable.

Plus the standing fleet checklist: JaCoCo, SonarQube, OpenAPI, Postman collection — not yet run/generated for this module.

---

# 18. Implementation Notes (What Was Actually Built)

Deviations from and additions to the design, discovered during implementation — same spirit as `PAYMENT_SERVICE_MODULE.md` §19.

- **`BookingEventPassenger` had no id field at all.** The design assumed `CheckIn.bookingPassengerId` could simply be read off the event, but `BookingEventPassenger` (skybook-common) only carried `name`/`seatNumber`/`travelClass`/`fareType`/`fare`/`checkInStatus` — no id. Added `bookingPassengerId` (nullable, additive) to the shared contract and populated it in booking-service's `BookingEventProducer` from `BookingPassengerResponse.id`. Same precedent as `BookingEvent.bookingId` being added when payment-service needed it. The consumer skips passengers missing it, loudly.
- **`documentVerified` defaults to `true` for event-driven `CheckIn` creation** rather than requiring yet another shared-contract addition for passport data — booking-service already validates passport validity before a booking can reach `CONFIRMED` (`BookingValidator.validatePassportValidForTravel`), so this is accurate by construction, not a shortcut. The manual `POST /api/checkins` path still takes it as a real request field.
- **Two `@PrePersist`-doesn't-fire-with-a-mocked-repository gaps**, found while writing service tests, not by inspection: `CheckInServiceImpl.createCheckIn` and `BoardingPassServiceImpl.generate` both relied on their entity's `@PrePersist` to default `status` (and `issuedAt`) — which only runs inside a real JPA flush, never when a repository is mocked, and is one indirection further than necessary even in production. booking-service already sets this kind of initial status explicitly in its builder (`BookingServiceImpl.java:85`) rather than leaning on `@PrePersist`; both methods now do the same. The two places that build `CheckInHistory` outside the state machine (`BoardingPassServiceImpl`'s own `recordHistory`, `BaggageServiceImpl.addBaggage`) had the identical gap for `changedAt` and got the same fix.
- **`@Value` field injection silently breaks outside a real Spring context.** `BoardingPassServiceImpl`, `ManifestServiceImpl`, and `CheckInFacade` originally declared their timing config (`boardingOpensMinutesBeforeDeparture`, `gateClosesMinutesBeforeDeparture`) as a `@Value`-annotated field alongside `@RequiredArgsConstructor`. Field injection only runs inside a real `ApplicationContext` — a plain `new` in a unit test leaves it at `0`, with no error. `CurrencyValidator`/`RefundCalculator`/`CheckInValidator` already avoided this by using explicit constructors; all three now do too.
- **`BoardingPass` is `@ManyToOne` to `CheckIn`, not `@OneToOne`** (§3.2) — a real design-doc self-contradiction found and fixed before any code was written: §3.1.1 originally said reissue "revokes-and-replaces rather than adding a second row," while §5.6/§6 said the opposite. Resolved in favor of the latter (new row per issuance, old one `REVOKED`) since it matches the append-only-history philosophy used everywhere else in this system (Payment ledger, Booking history). `reissuedAsId` added so a revoked pass can point at its replacement.
- **`ManifestService`'s "frozen snapshot" is a live recomputation, not a stored one.** The design doc's §3.5/§5.7 language ("frozen snapshot once FINALIZED") implied `CheckIn` rows get archived at finalize time; they don't — there's no manifest FK on `CheckIn` by design (§3.5's own reasoning: a flight has checked-in passengers before any manifest exists). `ManifestServiceImpl.getManifest`/`buildResponse` always compute counts and the passenger list live from current `CheckIn` rows, regardless of the stored `FlightManifest.status`. In practice `CheckIn` rows for a departed flight rarely change afterward, so this rarely diverges from a true snapshot — but it's a recomputation, not a read of frozen data. Noted in `ManifestService`'s own Javadoc too, not just here.
- **`NO_SHOW` semantics were resolved during design, not left as an open question into implementation** (§4.1/§15) — reachable from any pre-boarding state, meaning "didn't fly" whether or not the passenger ever checked in, rather than only from `CHECKED_IN` as a literal reading of the original brief implied. No separate `CLOSED` state exists.

## Test suite (132 tests, 0 failures)

| Layer | Classes | Highlights |
|---|---|---|
| Domain (46) | `CheckInStateMachineTest`, `CheckInValidatorTest`, `BoardingPassNumberGeneratorTest`, `BaggageTagGeneratorTest`, `BoardingPassTokenSignerTest`, `BaggageAllowanceCalculatorTest`, `BoardingGroupAssignerTest` | Full golden transition table incl. the resolved `NO_SHOW`/`CANCELLED` broadening exercised explicitly (not just implied by the table); constant-time-signature token forgery/tamper tests — the riskiest new code in the module. |
| Service (38) | `CheckInServiceImplTest`, `BoardingPassServiceImplTest`, `BaggageServiceImplTest`, `ManifestServiceImplTest` | Real repositories mocked, real domain collaborators used (state machine, validator, generators, signer, calculator) — same "mock only I/O" discipline as `PaymentServiceImplTest`. |
| Facade (12) | `CheckInFacadeTest` | Every collaborator mocked (service, boarding-pass service, both Feign clients, producer) — pure orchestration-order verification. |
| Consumer (8) | `BookingEventConsumerTest` | Idempotency and pre-enrichment-event handling mirrored from `payment-service`'s equivalent test. |
| Scheduler (4) | `NoShowSweepJobTest`, `ManifestFinalizationJobTest` | Delegation + silent-no-op-on-zero, same shape as `inventory-service`'s `SeatHoldExpiryJobTest`. |
| WebMvc (24) | `CheckInControllerTest`, `BoardingPassControllerTest`, `BaggageControllerTest`, `FlightManifestControllerTest` | Full HTTP-status contract per endpoint incl. the 422 boarding-pass-verification path, same `@WebMvcTest` + `@Import(SecurityConfig.class)` + `@MockitoBean` shape as `PaymentControllerTest`. |

Not yet built: JPA/Testcontainers, full-stack Kafka integration, concurrency — all need infrastructure (`Docker`) unavailable in the environment this module was built in.

---

*Implemented per this doc's build order (§16, steps 1-13) and tested through the service/facade/consumer layers (§17/§18) on `feature/checkin-management`. Mirrors `PAYMENT_SERVICE_MODULE.md`'s structure and rigor throughout.*
