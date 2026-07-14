# 💺 SkyBook Seat Selection & Seat Pricing — Design

---

## Project Information

| | |
|---|---|
| **Scope** | Free auto-assignment + paid seat selection; per-seat surcharge pricing by attribute; cabin-aware assignment; enforced at booking **and** check-in; original fare breakdown persisted per passenger |
| **Branch** | `feature/seat-selection` |
| **Status** | **Frozen after six review rounds — architecture final.** R6 (persistence rule only, no flow change): `SeatHold` snapshots `assignmentMode`/`listedSurcharge`/`chargedSurcharge` immutably at creation and every replay returns the stored values — money-level idempotency, robust to config changes between call and replay; mode-mismatch replays are 409s; legacy null-snapshot holds are released-and-replaced (self-audit: inventory has no Flyway and `seat_holds` is populated, so the columns land DB-nullable additive). Plus three doc-consistency cleanups (§1 strategy wording, V3 in the migration tests, §11 invoice-aggregate wording). Earlier: | R5: real `DRAFT` booking status + `DRAFT→CREATED` finalization + stale-draft sweep + **V3 check-constraint migration** (self-audit: the V1 baseline's status CHECK would reject DRAFT at the DB); defined hold-idempotency semantics under the flight lock (replay-safe, 409 on conflicting manual re-request); inventory authoritatively enforces check-in cabin + surcharge ceiling via an extended reserve contract (travelClass + maxAllowedSurcharge) with new-reservation compensation on local failure; Payment/Invoice gain baseFareTotal/seatSurchargeTotal aggregates with `fareBreakdown` untouched; the String `SeatAssignmentStrategy` contract is deleted, not repurposed. Earlier rounds: | R1: persisted breakdown, atomic inventory auto-hold, deferred paid check-in upgrades, availability-not-fares, listed-vs-charged, exit-row≠extra-legroom, refund policy. R2: explicit migration not ddl-auto, per-flight lock, USD-only, chargedSeatAssignmentMode rename, exhaustion + cabin-relative front rows. R3: auto-assignment moves into BookingFacade's draft→hold→finalize flow (seat_number nullable in draft); shared pessimistic flight-lock for manual AND auto holds; SeatPricingPolicy takes CabinPricingContext + deterministic ordering tuple; Flyway as the migration mechanism; entitlement-ceiling check-in rule. **R4: Flyway V1-baseline + V2-delta pair with `baseline-version: 1` and `ddl-auto: validate` (fresh-DB bootstrap fixed); BookingPayment created at finalization, not draft (fare/total/payment invariant at publish); CheckIn persists `seatSurchargeEntitlement`+`currency` snapshotted from the extended BookingEventPassenger (legacy null ⇒ 0); manual hold gains `travelClass`+`bookingPassengerId` (symmetric contracts, SeatHold persists the passenger id); §13 Flyway contradiction removed.** Implementation per §14. |

Goal: model seats the way real airlines do. A passenger who doesn't care gets a
low-demand seat **auto-assigned for free**; a passenger who wants a specific
seat — window, exit row, or a front-of-cabin spot — **pays a surcharge** for it,
on top of the cabin base fare. Cabin classes (Economy / Premium Economy /
Business / First) already exist in the data; this branch adds the *assignment
algorithm*, the *surcharge pricing*, the *free-vs-paid* distinction, and — the
review's central insight — a **persisted, immutable fare breakdown** so a
booking always shows what it actually charged, never what today's config says.

---

# Table of Contents

1. [Overview](#1-overview)
2. [Load-Bearing Findings](#2-load-bearing-findings)
3. [The Pricing Model — Listed vs Charged](#3-the-pricing-model--listed-vs-charged)
4. [Seat Surcharge — Where It's Computed & How It Composes](#4-seat-surcharge--where-its-computed--how-it-composes)
5. [Auto-Assignment (Free, Atomic in Inventory)](#5-auto-assignment-free-atomic-in-inventory)
6. [Manual Selection (Paid)](#6-manual-selection-paid)
7. [Cabin-Aware Assignment (Booking + Check-In)](#7-cabin-aware-assignment-booking--check-in)
8. [Persisted Fare Breakdown](#8-persisted-fare-breakdown)
9. [Check-In Seat Changes — Contained v1](#9-check-in-seat-changes--contained-v1)
10. [Refund & Invoice Policy](#10-refund--invoice-policy)
11. [API Changes & Ownership](#11-api-changes--ownership)
12. [Deferred / Out of Scope](#12-deferred--out-of-scope)
13. [Known Risks / Open Questions](#13-known-risks--open-questions)
14. [Build Order](#14-build-order)
15. [Testing Plan](#15-testing-plan)

---

# 1. Overview

Three capabilities plus one correctness backbone, all landing on seams the
codebase already built:

- **Free auto-assignment** — `BookingFacade` selects AUTO for a blank requested
  seat and delegates atomic seat selection to inventory-service (the old
  `SeatAssignmentStrategy` interface is deleted, §5.1). **Selection *and* hold
  happen atomically inside inventory-service** (review correction #2), not as a
  query-then-hold from booking, so there's no time-of-check/time-of-use race.
- **Per-seat surcharge pricing** — a config-driven `SeatPricingPolicy` in
  inventory-service, returning a **listed** surcharge on the seat map and both
  **listed and charged** surcharges on a hold.
- **Free-vs-paid rule** — auto-assigned seat → charged surcharge 0 (even if the
  seat has a non-zero listed price); a *chosen* seat → charged = its listed
  surcharge.
- **Persisted breakdown** (review correction #1) — each `BookingPassenger` stores
  `baseFare`, `seatSurcharge` (the amount actually charged), `chargedSeatAssignmentMode`,
  `currency`, and the all-in `fare`. Refunds, invoices, and check-in comparisons
  read the **persisted** values — historical charges are never recomputed from
  current inventory config.

No new microservice. Seat pricing stays a small config-driven policy inside
inventory (which owns seats), behind an interface the Phase-2 Pricing Service can
later take over. **Cabin base-fare ownership stays with booking's
`FareCalculator`** — inventory returns seat/cabin *availability* only, never
fares (review correction #4).

---

# 2. Load-Bearing Findings

Confirmed by reading the code, not assumed:

1. **The auto-assignment seam exists but the call site must move** (corrected in
   round 3). `SeatAssignmentStrategy` is an interface; `ManualSeatAssignmentStrategy`
   rejects a blank seat. But it's called *inside* `BookingServiceImpl.createBooking`'s
   transaction, before the passenger is saved — so it has no IDs to hand inventory,
   returns only a `String`, and can't host Feign I/O. Auto-assignment therefore
   moves out of `createBooking` into `BookingFacade`'s post-commit hold step
   (§5.1) — a real flow change, not just a new bean. This is the review's mandatory
   correction.
2. **Seat categories and attributes are already modeled.** `AircraftSeat` carries
   `seatType` (ECONOMY / PREMIUM_ECONOMY / BUSINESS / FIRST), `position` (WINDOW /
   MIDDLE / AISLE), `exitRow` (boolean), `rowNumber`. **There is no
   `extraLegroom` attribute** — so this branch prices the **exit-row** flag, and
   does *not* claim exit-row ⇔ extra-legroom (review correction #6); a distinct
   `extraLegroom` attribute is deferred (§12).
3. **"Not every flight has every cabin" is already true** — sellable seats come
   from the aircraft's seat map; the seeded A320 has only Business+Economy, the
   777 all four. The cabin-availability rule falls out of the model; it needs a
   clear error and an availability lookup (§7).
4. **Base fare per cabin already exists and belongs to booking** — `FareCalculator`
   maps TravelClass → base fare (Eco 100 / Premium 180 / Business 350 / First
   700) × a FareType multiplier, in `BigDecimal`, `HALF_UP`, scale 2. FareType
   affects price, so **fares cannot be computed by inventory** — it doesn't know
   the fare type (review correction #4).
5. **Inventory already exposes a seat map and holds specific seats** (`/seat-map`,
   `/seats`, `/hold`), and **check-in already has `PATCH /{id}/seat`** — so every
   surface this branch touches already exists; it's adding fields and one atomic
   endpoint, not new subsystems.
6. **Double-booking is already guarded** by a `(flightId, seatNumber)` uniqueness
   constraint plus inventory's hold counts — the backstop for the atomic auto-hold
   (§5) and the concurrency test (§15).
7. **Refund today applies a fare-type percentage to the whole passenger fare**
   (payment-service `RefundCalculator` / SAVER cancellation fee). Once the fare
   includes a seat surcharge, cancellation refunds the surcharge at the same
   percentage **unless we say otherwise** — so the policy must be explicit
   (review correction #7, resolved in §10).

---

# 3. The Pricing Model — Listed vs Charged

Two independent layers, and — the review's key clarification — **two distinct
surcharge values per seat**:

```
   passenger fare  =  CABIN BASE FARE            +  CHARGED SEAT SURCHARGE
                      (TravelClass × FareType,      (0 if the seat was
                       FareCalculator, exists)       AUTO-assigned; the seat's
                                                     listed surcharge if MANUAL)
```

- **listedSurcharge** — what the seat *is worth* by its attributes (a window is
  $12 whether or not anyone paid it). Shown on the seat map.
- **chargedSurcharge** — what this passenger *actually paid*: `0` for an
  auto-assigned seat (even if it happens to be a window), the listed amount for a
  chosen seat. This is what gets **persisted** (§8) and drives refunds/invoices.

So an auto-assigned 12A returns `{assignmentMode: AUTO, listedSurcharge: 12.00,
chargedSurcharge: 0.00}`; a manually chosen 12A returns `{assignmentMode: MANUAL,
listedSurcharge: 12.00, chargedSurcharge: 12.00}`. Booking persists
`chargedSurcharge` as the passenger's `seatSurcharge`.

All amounts are `BigDecimal`, `HALF_UP`, scale 2, in **USD** (v1's single
currency — §8; matches every existing booking/payment row).

---

# 4. Seat Surcharge — Where It's Computed & How It Composes

**Decision: a config-driven `SeatPricingPolicy` inside inventory-service** — not a
new Pricing Service, not hard-coded in booking. It is a pure function of the
seat's attributes **plus its cabin context** (review round 3, correction #3),
driven by `inventory.seat-pricing.*` in `application.yml`:

```java
BigDecimal calculateListedSurcharge(AircraftSeat seat, CabinPricingContext cabin);

public record CabinPricingContext(int firstCabinRow, int frontRowCount) {}
```

`rowNumber` alone cannot decide "front of cabin" — on the 777, Business is rows
3–8, so `3A` is a front-of-cabin seat but `rowNumber=3` doesn't say so. The
policy needs the cabin's first row: `frontOfCabin = seat.rowNumber() <
cabin.firstCabinRow() + cabin.frontRowCount()`. Inventory derives the context
from the flight's seat map (min row per `seatType`) before calling the policy.

**Composition — highest applicable tier, NOT additive** (review correction #5).
A seat is priced at the **single most valuable applicable tier**, the way
airlines actually sell a seat as one category:

| Applicable attribute (highest wins) | Listed surcharge (config) |
|---|---|
| Exit row | $30 |
| Front-of-cabin (first N rows of the cabin) | $15 |
| Window (standard) | $12 |
| Aisle (standard) | $8 |
| Standard middle economy | $0 (the free auto-assign pool) |

A window *and* exit-row seat is charged **$30** (the exit-row tier), not
$12 + $30. This is `max(applicable tiers)`, stated so implementation can't drift
into silently summing them.

- Surcharge is **derived at read time**, not stored on `aircraft_seats` — re-pricing
  is a config change, never a data migration. (A per-seat `surcharge_override`
  column is deferred, §12.)
- The policy sits behind a `SeatPricingPolicy` interface so the future Pricing
  Service can implement it (or inventory can delegate to it) with no booking
  change.
- **The hold response is the authoritative price** (smaller-addition item): if a
  seat-map *preview* price and the hold-time price ever differ (config changed
  mid-session), the hold's `chargedSurcharge` is what the booking persists.

---

# 5. Auto-Assignment, the Booking Flow, and Locking

## 5.1 The flow must be draft → hold/assign → finalize (review round 3, correction #1)

**The original claim that the `SeatAssignmentStrategy` call site stays untouched
was wrong** — verified against the code. `BookingServiceImpl.createBooking()` is
`@Transactional`, builds an **unsaved** `BookingPassenger` (IDENTITY id → no id
yet), and calls `resolveSeatNumber(...)` *before* `save`. But the atomic auto-hold
needs `bookingId` + `bookingPassengerId`, which don't exist yet; the strategy
returns only a `String` (can't carry surcharge/mode); and calling inventory there
would put Feign I/O **inside the booking DB transaction** — exactly what
`BookingFacade` (deliberately **not** `@Transactional`) exists to avoid.

The fix reuses the facade orchestration the codebase already has (`createBooking`
→ commit → `holdSeatsOrCompensate` → publish):

```
BookingFacade.createBooking
  → bookingService.createDraftBooking(...)          [tx commit: booking + passenger IDs exist,
                                                     seat_number NULL, fare = baseFare only,
                                                     NO BookingPayment yet]
  → for each passenger: inventory hold (manual OR atomic auto)  [outside any booking tx]
        → collect SeatAssignmentResult{ seatNumber, listedSurcharge,
                                        chargedSurcharge, mode }
  → bookingService.finalizeSeatAssignments(...)      [ONE tx commit that synchronizes ALL
                                                     money fields: per-passenger seat_number,
                                                     seatSurcharge, chargedSeatAssignmentMode,
                                                     fare = base + charged; Booking.totalFare;
                                                     and CREATES BookingPayment(PENDING,
                                                     finalTotal, USD)]
  → publish BookingCreated                           [event carries the FINAL totals]
On any failure: release already-held seats → cancel the draft booking → rethrow.
```

- **`BookingPayment` is created at finalization, not at draft** (review round 4,
  correction #2). Today `createBooking` builds the payment snapshot inline with
  `.amount(totalFare)` — in a draft flow that amount would go stale the moment a
  surcharge lands. Rather than "create then patch," the draft stage creates **no**
  payment snapshot; `finalizeSeatAssignments` computes the final total and creates
  `BookingPayment(PENDING, finalTotal, USD)` in the same transaction that writes
  the seat/fare fields. Invariant, stated for tests: at `BookingCreated` publish
  time, `sum(passenger.fare) = Booking.totalFare = BookingPayment.amount` —
  payment-service's auto-created Payment (from the event) then matches too.
- **`booking_passengers.seat_number` becomes nullable** for the draft stage (it is
  `nullable=false` today) — a column change in the same migration as §8. Postgres
  treats NULLs as distinct under `uk_flight_seat (flight_id, seat_number)`, so
  concurrent drafts on the same flight don't collide (verified semantics, not
  assumed).
- **The `String resolveSeatNumber(...)` strategy contract is retired, not
  repurposed** (review round 5, smaller cleanup). Seat resolution now happens in
  the facade's hold step, so keeping an interface whose signature promises
  something else invites misuse. `SeatAssignmentStrategy` +
  `ManualSeatAssignmentStrategy` are **deleted**; the facade decides the mode
  directly (blank `seatNumber` ⇒ AUTO, non-blank ⇒ MANUAL) and inventory's hold
  response is the authoritative resolution.

## 5.1a Draft lifecycle: a real `DRAFT` status (review round 5, correction #1)

The multi-transaction flow creates a state the current machine doesn't model: a
committed booking with `seat_number = NULL` and no payment. Today's enum starts
at `CREATED`, `prePersist` defaults to it, and `CREATED → CONFIRMED` is legal —
so a JVM crash between draft-commit and finalize would leave an orphan that the
back-office `confirmBooking()` could confirm **with no seat and no payment**.
Fix, in full:

- `BookingStatus` gains **`DRAFT`** (first value); `createDraftBooking` sets it
  explicitly. Transitions added to `BookingStateMachine`:
  `DRAFT → CREATED` (finalize) and `DRAFT → CANCELLED` (assignment failure or
  sweep). `CONFIRMED` remains reachable **only from `CREATED`** — the orphan
  window closes structurally, not by convention.
- `BookingCreated` publishes **only after `DRAFT → CREATED`** (finalize commit).
- **Stale-draft sweep**: a scheduled job (same pattern as inventory's
  `SeatHoldExpiryJob`) cancels `DRAFT` bookings older than a configured TTL
  (default 15 min, matching the hold TTL) — inventory holds already expire on
  their own; this keeps booking's table from accumulating permanent orphans.
- **V3 migration required** (self-audit catch, same class as round 5's findings):
  the V1 baseline carries
  `bookings_booking_status_check CHECK (booking_status IN ('CREATED','CONFIRMED','CANCELLED','COMPLETED'))`
  — without `V3__add_draft_booking_status.sql` (drop + re-add the check including
  `DRAFT`), every draft insert fails at the database regardless of the enum.

## 5.2 Atomic auto-hold endpoint (inventory owns select-and-hold)

- **`POST /api/inventory/flights/{flightId}/holds/auto`**
  `{ "bookingId": 123, "bookingPassengerId": 456, "travelClass": "ECONOMY" }`
  → inventory orders candidates by a **deterministic tuple** (review round 3,
  correction #3), not overlapping categories:
  1. exclude exit rows (eligibility deferred, §12)
  2. non-front-of-cabin before front-of-cabin
  3. MIDDLE before AISLE before WINDOW
  4. tie-break by (rowNumber, seatNumber) for determinism
  — atomically holds the first, returns
  `{ seatNumber, assignmentMode: AUTO, listedSurcharge, chargedSurcharge: 0.00 }`.
- **Always free** — `chargedSurcharge = 0.00` regardless of the physical seat
  landed on. "Front of cabin" is relative to the **cabin's** first row (§4).
- **Exhaustion** — if only exit rows remain (excluded) or none, inventory returns a
  clear `NoSeatAvailableException`, never seats an ineligible passenger.

## 5.3 One shared flight-level lock for BOTH manual and auto holds (review round 3, correction #2)

A pessimistic lock on auto-hold *alone* serializes AUTO↔AUTO but **not
AUTO↔MANUAL** — the manual `holdSeat()` path today uses only
`FlightInventoryRepository.findByFlightId(...)` + `@Version` optimistic locking, so
a manual selector and an auto-assign can inspect the same seat as available
concurrently. Both paths must take the **same** flight-level pessimistic lock:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select fi from FlightInventory fi where fi.flightId = :flightId")
Optional<FlightInventory> findByFlightIdForUpdate(Long flightId);
```

`holdSeat()` **and** `autoHoldSeat()` acquire this before any availability check.
**Policy decision (stated, not left implicit):** every operation that mutates the
`available/held/reserved/blocked` counters (whose invariant is
`available + held + reserved + blocked = total`) uses this **pessimistic**
flight-level lock — we do not mix pessimistic auto with optimistic manual on the
same aggregate. `@Version` stays as a second line of defence, but serialization is
the lock's job.
- Concurrency test (§15) proves: two simultaneous auto-holds on the same flight →
  **different seats**, **no rollback-only error**, inventory counts still correct.

---

# 6. Manual Selection (Paid)

When the passenger supplies a seat number:

- Validated: exists on the aircraft, AVAILABLE, and its `seatType` matches the
  passenger's booked `travelClass` (§7). **For inventory to enforce that rule it
  must be told the travel class** (review round 4, correction #4) — today's
  `HoldSeatRequest` is only `{flightId, seatNumber, bookingId}`, so cabin
  validation at hold time was impossible as drafted. The manual and auto hold
  contracts become symmetrical:

  ```
  MANUAL /hold          : { flightId, seatNumber, bookingId, bookingPassengerId, travelClass }
  AUTO   /holds/auto    : { flightId,             bookingId, bookingPassengerId, travelClass }
  ```

  Inventory owns the authoritative cabin validation on **both** paths.
- **`SeatHold` persists `bookingPassengerId` AND snapshots the pricing decision**
  (review rounds 5+6). Today it stores only `bookingId`, no mode, no prices —
  which makes replay *row*-idempotent but not *money*-idempotent: a replayed
  request couldn't know whether the stored hold was AUTO or MANUAL (an AUTO
  retry against a MANUAL 12A hold would silently turn a charged $12 into $0),
  and recomputing the price from current config would let a config change
  between call and replay alter what the client is told
  (10:00 hold at $12 → config bump → 10:01 replay says $15 — not idempotent).
  So the hold row snapshots, immutably at creation
  (`nullable = false, updatable = false`):

  ```
  assignmentMode    AUTO | MANUAL
  listedSurcharge   policy result at hold time
  chargedSurcharge  = listedSurcharge (MANUAL) | 0.00 (AUTO)
  ```

  **Replay always returns the stored values — an existing hold's price is never
  recalculated.** The hold response is authoritative *because* it is persisted.
- The idempotency semantics are **defined behavior, not an implication of the
  columns existing**. Under the shared flight lock, every hold request first
  looks up the passenger's active hold
  (`findByFlightInventoryIdAndBookingPassengerIdAndStatus(…, ACTIVE)`, backed by
  an index on `(flight_inventory_id, booking_passenger_id, status)`), then:

  | Request | Existing ACTIVE hold | Behavior |
  |---|---|---|
  | any | none | create hold with snapshot |
  | AUTO | AUTO | return stored hold (mode, listed, charged) — idempotent |
  | AUTO | MANUAL | **409** — conflicting assignment mode, never a silent free-ification |
  | MANUAL same seat | MANUAL | return stored hold — idempotent |
  | MANUAL different seat | MANUAL | **409** — explicit release/change flow required |
  | MANUAL | AUTO | **409** — conflicting assignment mode |

- **Legacy-hold transition** (self-audit, same class as the round-6 catch):
  inventory has no Flyway and `seat_holds` is populated, so the three snapshot
  columns land as **DB-nullable additive** columns (`ddl-auto: update` can add
  those), with the service always populating them for new rows. A pre-branch
  ACTIVE hold (null snapshot) is **not replay-eligible**: the lookup releases it
  through the normal release path (counts stay correct) and creates a fresh
  snapshotted hold. Exposure window is bounded by the 15-minute hold TTL after
  deploy, then null-snapshot ACTIVE holds cannot exist.
- Held via the `/hold` path, whose response now carries
  `{assignmentMode: MANUAL, listedSurcharge, chargedSurcharge}` with
  `chargedSurcharge == listedSurcharge`.
- A chosen *standard middle economy* seat is `chargedSurcharge: 0.00` — so "I want
  to pick my own seat but I'm not paying for a premium one" is honored; only
  window/aisle/front/exit-row selections bill.

---

# 7. Cabin-Aware Assignment (Booking + Check-In)

- `travelClass` determines occupiable seats: an ECONOMY ticket → ECONOMY seat,
  etc. Enforced on **both** auto and manual paths, **and at check-in seat change**
  (smaller-addition item — a check-in change can't cross cabins either).
- **A flight only offers the cabins its aircraft has.** Booking FIRST on an A320
  flight fails with a clear *"this flight has no First-class cabin"*, not a
  generic "seat unavailable". A **cabin-availability lookup** (§11) lets a client
  ask which cabins a flight sells before booking.

---

# 8. Persisted Fare Breakdown

**The review's #1 point.** `BookingPassenger` gains persisted, immutable-at-charge
fields — the total alone is insufficient because config changes, an old booking
must still show its original breakdown, and check-in comparisons need the
surcharge *actually paid* (an auto window physically worth $12 but charged $0):

| New field on `BookingPassenger` | Meaning |
|---|---|
| `baseFare` | cabin base fare at booking time (`FareCalculator` output) |
| `seatSurcharge` | the **charged** surcharge (0 for AUTO, listed for MANUAL) |
| `chargedSeatAssignmentMode` | `AUTO` / `MANUAL` — how the *original charge* was computed. Named with `charged`/`original` intent (review round 2) so a later free seat change never overwrites the historical pricing explanation. |
| `currency` | ISO-4217; **`USD` in v1** (see below) |
| `fare` (existing) | all-in total = `baseFare + seatSurcharge` |

- **Schema change is a real Flyway migration — with a baseline-plus-delta pair,
  not a lone V1 delta (review round 4, correction #1).** `ddl-auto` never runs
  data transforms and rejects adding `NOT NULL` to a populated table; a hand-run
  script under `scripts/seed` has no execution mechanism in CI/Compose/K8s. But a
  lone `V1__add_columns.sql` also fails on a **fresh** database: Flyway runs
  *before* Hibernate, so V1 would ALTER a `booking_passengers` table that doesn't
  exist yet. So booking-service gets:

  ```
  V1__baseline_booking_schema.sql          # full CREATE TABLEs (bookings,
                                           # booking_passengers, passengers,
                                           # booking_payments, history...)
  V2__add_booking_passenger_fare_breakdown.sql   # ALTER ADD (nullable) + DROP
                                           # NOT NULL on seat_number → UPDATE
                                           # backfill (baseFare=fare,
                                           # seatSurcharge=0, mode='MANUAL',
                                           # currency='USD') → SET NOT NULL
  ```

  With `baseline-on-migrate: true` + **`baseline-version: 1`** an *existing*
  Hibernate-created database is adopted as V1 (V1 skipped, V2 runs); a *fresh*
  database runs V1 then V2. Both paths converge on the same schema — the
  "runs automatically in Compose/CI/K8s" claim is then true universally.
  booking-service moves to **`ddl-auto: validate`** (Flyway owns the schema;
  Hibernate only checks it). The dependency pattern (`flyway-core` +
  `flyway-database-postgresql`) already exists in auth-service. The interim
  `scripts/seed/booking_breakdown_backfill.sql` is superseded and removed.
  (Flyway fleet-wide is a natural follow-up branch, §12.)
- **Currency: SkyBook v1 supports `USD` only (review round 2, correction #3).**
  Every existing booking/payment row is already USD (`DEFAULT_CURRENCY="USD"`), so
  booking, payment, invoice, and seat-pricing config are all USD; the persisted
  `currency` is `"USD"`. Multi-currency moves to the future Pricing Service. (The
  reviewer suggested GBP; USD chosen instead to match the data already on disk —
  the surcharge examples elsewhere read as USD.)
- **Refunds, invoices, and seat-change comparisons read these persisted values**,
  never recompute from current inventory config. This is the invariant the whole
  correction rests on.
- The booking response exposes the breakdown; the total billed stays the existing
  `fare` field, so payment's happy path is unchanged (it still charges `fare`).

---

# 9. Check-In Seat Changes — Contained v1

**The review's #3 point, resolved decisively toward containment.** A paid upgrade
at check-in needs a real payment saga (hold new → charge delta → confirm →
release old → compensate on any failure) plus a supplemental-charge operation in
payment-service. That is its own design; half-building it here is worse than
scoping it out. So v1:

- **The persisted `seatSurcharge` is an *entitlement ceiling*, not the current
  seat's price** (review round 3 clarification). It is the amount originally
  **paid**, and a free seat change never lowers it. Example: paid $30 for an
  exit-row seat → voluntarily move to a $0 seat (no refund) → later move to a $15
  front seat is **allowed**, because the target ($15) is ≤ the $30 entitlement.
  So implementation compares the target seat's listed surcharge against the
  **persisted `seatSurcharge`**, and a free downgrade does **not** overwrite that
  field (nor `chargedSeatAssignmentMode`) — a future dev must not compare against
  the *current* seat's surcharge.
- **Allowed:** target listed surcharge **≤** persisted `seatSurcharge` (same-price,
  downgrades, and re-upgrades within the ceiling). Cabin compatibility enforced (§7).
- **No refund** on a voluntary downgrade (documented simplification).
- **Rejected:** any change whose target listed surcharge **exceeds** the
  entitlement ceiling, with a clear response:
  > *"Paid seat upgrades must be completed through Manage Booking."*

**Where check-in reads the ceiling from (review round 4, correction #3):**
checkin-service deliberately snapshots booking data from `BookingEvent CONFIRMED`
and must NOT gain a synchronous booking-service dependency. But its `CheckIn`
entity has no surcharge field and `BookingEventPassenger` carries only the total
`fare` — so both sides of the pipe gain fields:

- `BookingEventPassenger` (shared, `skybook-common`) gains `baseFare`,
  `seatSurcharge`, `chargedSeatAssignmentMode`, `currency` (additive — this is the
  §10 breakdown-preservation change).
- `CheckIn` gains `seatSurchargeEntitlement` (`BigDecimal`) + `currency`, populated
  from `BookingEventPassenger.seatSurcharge` when the CONFIRMED event creates the
  check-in row. Nullable, additive columns — checkin's `ddl-auto: update` handles
  them without a migration.
- **Legacy rows** (check-ins created before this branch, or from events without
  the new fields): `null` entitlement is treated as **0** — only free seats
  reachable, the conservative default.

**Where the ceiling is *enforced* (review round 5, correction #3):** check-in
owns the entitlement, but only **inventory** knows the target seat's current
listed surcharge and cabin — so the enforcement point is the reservation call,
not a check-in-side comparison against data it doesn't have. The direct-reserve
contract (used by `changeSeat`) is extended:

```
{ flightId, seatNumber, bookingId, bookingPassengerId,
  travelClass, maxAllowedSurcharge }        # check-in passes
                                            # maxAllowedSurcharge = seatSurchargeEntitlement
```

Inventory, under the same shared flight lock as every other seat mutation:
find seat → verify `seatType == travelClass` → compute current listed surcharge
→ verify `listedSurcharge <= maxAllowedSurcharge` → reserve → return the
`listedSurcharge`. Ownership stays clean: check-in owns *what the passenger is
entitled to*, inventory owns *what the seat costs and which cabin it's in*.

**Compensation (round-5 catch, verified in the current code):** today's
`CheckInFacade.changeSeat()` reserves the new seat and then updates the local
check-in row with **no rollback if that update throws** — the new seat stays
reserved and the old one active. Fixed:

```
reserve new seat → local changeSeatNumber() fails
  → cancelReservationQuietly(NEW seat)   # compensate
  → rethrow original failure
(old seat is only cancelled AFTER the local update succeeds — unchanged)
```

Paid check-in upgrades + the payment saga + a payment-service supplemental-charge
operation are a **separate future design** (§12).

---

# 10. Refund & Invoice Policy

**The review's #7 point, made an explicit declared policy.**

- **v1 policy: the seat surcharge follows the booking's fare-type refund policy.**
  The `seatSurcharge` is part of the passenger `fare`, so on cancellation it is
  refunded at the same percentage the fare-type rules already apply (e.g. a SAVER
  cancellation fee applies to the whole fare including the surcharge). This
  requires **no change to `RefundCalculator`** — it keeps operating on the total
  fare — and is a defensible v1.
- The alternative (**seat surcharge as a non-refundable ancillary**) is
  deliberately **deferred** (§12): it needs a separate ancillary line item in
  payment/invoice and a distinct refund path.
- **Breakdown preservation — matched to the actual models (review round 5,
  correction #4).** The current `Invoice` has no line items (only
  `subtotal/taxAmount/discount/grandTotal/currency`), and `Payment.fareBreakdown`
  is a purpose-built `FARETYPE:amount;…` string that `RefundCalculator` parses —
  **its format must not change in this branch** or refunds break while the design
  claims they're untouched. So, no invented line-item subsystem:

  - `Payment` gains two **nullable additive** aggregate snapshots:
    `baseFareTotal = Σ passenger.baseFare` and
    `seatSurchargeTotal = Σ passenger.seatSurcharge`, populated from the extended
    `BookingEventPassenger` fields when the CREATED event builds the payment.
  - `Invoice` snapshots the same two fields alongside its existing columns:
    `subtotal / baseFareTotal / seatSurchargeTotal / taxAmount / discount /
    grandTotal` — an honest breakdown without an `InvoiceLineItem` subsystem.
  - `fareBreakdown` stays byte-for-byte as-is, feeding `RefundCalculator` exactly
    as today. Legacy payments/invoices have `null` aggregates (pre-branch rows) —
    displayed as "breakdown unavailable," never fabricated.

---

# 11. API Changes & Ownership

Additive; ownership boundaries corrected per review #4.

| Endpoint | Change | Owner |
|---|---|---|
| `POST /api/bookings` | `seatNumber` per passenger truly optional — omit → free auto-assign; supply → paid selection | booking |
| `POST /api/inventory/flights/{id}/holds/auto` (new) | atomic select-preferred-and-hold; returns seat + listed/charged surcharge | inventory |
| `GET /api/inventory/flights/{id}/seat-map` | each seat gains `listedSurcharge` + `available` | inventory |
| `GET /api/inventory/flights/{id}/cabins` (new) | **availability only** — `{travelClass, totalSeats, availableSeats}`, **no fares** | inventory |
| `POST /api/bookings/quote` (new) | assembles fare options: inventory cabin availability **+** booking base fare (`FareCalculator`, incl. FareType) **+** seat surcharge options → "Economy from X, Business from Y" | booking |
| `PATCH /api/checkins/{id}/seat` | applies the contained-v1 rule (§9) | check-in |
| Booking response / events | gain per-passenger `baseFare` + `seatSurcharge` + `chargedSeatAssignmentMode` alongside the all-in `fare` | booking |
| Payment / Invoice | gain **aggregate** snapshots `baseFareTotal` + `seatSurchargeTotal` (§10) — no per-passenger line items; `fareBreakdown` untouched | payment |

**Inventory never returns a fare.** The `/cabins` endpoint reports seats; the
booking `/quote` endpoint is the only place cabin availability, base fare, and
seat surcharges are combined — so `FareCalculator`'s rules are never duplicated in
inventory.

---

# 12. Deferred / Out of Scope

- **Paid seat upgrades at check-in + the payment saga + a payment-service
  supplemental/ancillary-charge operation** (§9) — the biggest deferred piece;
  its own design.
- **Full Pricing Service** (dynamic pricing, fare rules, taxes, promotions,
  coupons) — Phase 2; this branch is seat surcharge behind an interface it can
  take over (§4).
- **A distinct `extraLegroom` seat attribute** (+ migration) — v1 prices `exitRow`
  only and does not conflate the two (§2.2).
- **Seat surcharge as a non-refundable ancillary** (separate line item + refund
  path) — v1 folds it into the fare-type refund policy (§10).
- **Per-seat manual price overrides** (`surcharge_override` column) — attributes
  drive price for now.
- **Exit-row passenger eligibility** (no minors/infants/reduced-mobility in exit
  rows — the `SeatAllocationValidator` the `AircraftSeat` comment anticipates) —
  deferred; auto-assign already avoids exit rows, and manual exit-row selection is
  unrestricted in v1.
- **Group/family seat-adjacency** in auto-assign; **interactive-selection holds**
  (holding a seat while a user browses) — frontend-era concerns.
- **Flyway fleet-wide** — this branch introduces Flyway in booking-service only
  (§8); rolling it across the other services (retiring `ddl-auto: update`) is a
  natural dedicated follow-up, not bundled here.

---

# 13. Known Risks / Open Questions

- **Backfill of existing bookings** — handled by the Flyway V1-baseline + V2-delta
  pair (§8): existing databases are baselined at V1 and V2 backfills
  (`baseFare=fare`, `seatSurcharge=0`, `chargedSeatAssignmentMode='MANUAL'`,
  `currency='USD'`, then `SET NOT NULL`); fresh databases run V1 then V2. The
  earlier `scripts/seed/booking_breakdown_backfill.sql` is superseded and removed
  (round-4 cleanup of a round-2/round-3 contradiction: §8 and this section
  previously disagreed about whether Flyway was in-branch or deferred — it is
  **in this branch**, for booking-service; fleet-wide rollout stays deferred, §12).
- **Currency is decided, not open: USD only in v1** (§8). Every existing row is
  already USD; booking, payment, invoice, and seat-pricing config are all USD.
  Multi-currency is a Pricing-Service-era concern. The only remaining *convention*
  cleanup is that `FareCalculator` returns a unitless `BigDecimal` the caller
  labels USD — cosmetic, not a correctness gap.
- **Surcharge numbers are pre-tuning defaults** — config, changeable without a
  deploy.
- **Auto-assign "low demand" ordering is a heuristic** — a config-ordered
  preference list, not hard-coded; not load-tested.
- **Atomic auto-hold contention** — the endpoint holds under the row/constraint
  guard; a burst of auto-assigns serializes on the flight's inventory. Acceptable
  at this scale; a documented limitation if the gateway ever fronts high booking
  concurrency.

---

# 14. Build Order

1. **Persisted breakdown via Flyway, baseline + delta** (§8, round-4 shape) —
   introduce Flyway in booking-service with the migration **pair**:
   `V1__baseline_booking_schema.sql` (full CREATE TABLEs, so a fresh database
   bootstraps without Hibernate) and
   `V2__add_booking_passenger_fare_breakdown.sql` (adds the four columns, drops
   NOT NULL on `seat_number` for the draft stage, backfills, sets NOT NULL).
   Config: `baseline-on-migrate: true`, **`baseline-version: 1`** (existing
   Hibernate-created DBs adopt V1 and run only V2), and booking-service moves to
   **`ddl-auto: validate`**. **Supersedes the interim
   `scripts/seed/booking_breakdown_backfill.sql` shipped in the first pass — remove
   it.** Verify BOTH paths: an existing populated DB (baseline+V2) and a fresh
   empty DB (V1+V2) converge on the same schema; existing bookings still
   read/refund; migration runs in Compose/CI/K8s automatically.
2. **`SeatPricingPolicy` in inventory** (§4) — `(seat, CabinPricingContext)` →
   listed-surcharge, `max` composition, config-driven; surface `listedSurcharge`
   on the seat map; unit-tested (highest-tier + cabin-relative front-row).
3. **Atomic auto-hold + shared flight lock** (§5) — `findByFlightIdForUpdate`
   (`PESSIMISTIC_WRITE`) adopted by **both** `holdSeat` and the new
   `POST /holds/auto`; deterministic ordering tuple; concurrency test proving
   AUTO↔MANUAL and AUTO↔AUTO both serialize (different seats, no rollback-only,
   counts correct).
4. **Draft → hold → finalize flow in `BookingFacade`** (§5.1/§5.1a) —
   `V3__add_draft_booking_status.sql` (replace the status CHECK constraint to
   admit DRAFT) + `DRAFT` in the enum/state machine; `createDraftBooking`
   (DRAFT, seat null, **no payment row**) → per-passenger inventory hold
   (manual/auto, idempotent per §6's table) → `finalizeSeatAssignments`
   (fares + totals + `BookingPayment` created + `DRAFT→CREATED`) → publish;
   compensation on failure (release holds → `DRAFT→CANCELLED`); stale-draft
   sweep job; **delete `SeatAssignmentStrategy`/`ManualSeatAssignmentStrategy`**
   (facade decides mode from blank/non-blank). Booking omit-seat → free auto
   seat end-to-end.
5. **Manual-selection surcharge + fare assembly** (§3/§6/§8) — finalize persists
   base + charged surcharge + mode; response breakdown.
6. **Cabin availability + booking quote** (§7/§11) — inventory `/cabins`
   (availability only) + booking `/quote` (assembles fares); clear "no such cabin"
   error.
7. **Check-in contained-v1 rule** (§9) — extended reserve contract
   (`travelClass` + `maxAllowedSurcharge`), inventory enforces cabin + ceiling
   under the shared lock and returns the listed surcharge; `changeSeat`
   compensation (cancel the NEW reservation if the local update fails);
   entitlement snapshot consumed from the event.
8. **Refund/invoice breakdown preservation** (§10) — `Payment.baseFareTotal`/
   `seatSurchargeTotal` + `Invoice` snapshots of the same; `fareBreakdown`
   byte-identical; confirm refund uses the persisted total.
9. **Design doc → implemented + Implementation Notes**, house pattern.

---

# 15. Testing Plan

| Layer | What's tested |
|---|---|
| Seat pricing | `SeatPricingPolicy`: each attribute → configured listed surcharge; **window+exit-row = exit-row tier, not the sum**; middle economy = 0 |
| Auto-assign (atomic) | omit seat → in-cabin low-demand seat held; `chargedSurcharge=0` even if only windows remain; preference ordering respected |
| Manual paid | choose window/exit-row → `chargedSurcharge = listedSurcharge`; fare = base + surcharge |
| Listed vs charged | auto 12A → charged 0 / listed 12; manual 12A → charged 12 / listed 12; **persisted** `seatSurcharge` matches charged |
| Persisted breakdown | `baseFare`+`seatSurcharge`+`mode`+`currency` stored; response/invoice/event breakdown correct; refund reads persisted total, not recomputed |
| Cabin rules | FIRST on an A320 flight → clear "no First cabin"; ECONOMY ticket can't take a BUSINESS seat — at booking **and** check-in |
| Check-in v1 | downgrade/same → allowed, no refund; upgrade → rejected with the Manage-Booking message; cross-cabin → rejected |
| Concurrency | two atomic auto-assigns racing the last seat → **different seats, no rollback-only error, counts correct**; auto vs MANUAL hold racing the same seat → serialized by the shared flight lock |
| Migration (rounds 4-6) | fresh empty DB → **V1+V2+V3** produce the full schema (incl. the DRAFT-admitting status CHECK); existing populated DB → baselined at 1, **V2+V3** run, backfill correct; both converge on the identical schema (`ddl-auto: validate` passes on each) |
| Hold snapshot idempotency (round 6) | replay returns the STORED mode/listed/charged even after a pricing-config change between call and replay; AUTO request against a MANUAL hold → 409 (never a silent $12→$0); legacy null-snapshot ACTIVE hold → released and re-held with a fresh snapshot, counts correct |
| Payment invariant (round 4) | at `BookingCreated` publish: `sum(passenger.fare) = Booking.totalFare = BookingPayment.amount`; no payment row exists during the draft stage |
| Entitlement snapshot (round 4) | CONFIRMED event with breakdown fields → `CheckIn.seatSurchargeEntitlement` populated; event without them (legacy) → entitlement null ⇒ treated as 0 (only free seats reachable at check-in) |
| Manual-hold cabin enforcement (round 4) | manual `/hold` with `travelClass=ECONOMY` on a BUSINESS seat → rejected by inventory; `bookingPassengerId` persisted on the hold |
| Draft lifecycle (round 5) | crash window simulated: a committed DRAFT cannot be confirmed (`DRAFT→CONFIRMED` rejected by the state machine); finalize → `CREATED` and only then is `BookingCreated` published; sweep cancels a TTL-expired DRAFT; V3 constraint allows DRAFT inserts |
| Hold idempotency (round 5) | replayed MANUAL hold (same seat) → same hold returned, counts unchanged; AUTO retry → existing hold returned; different manual seat with an active hold → 409 |
| Check-in ceiling enforcement (round 5) | reserve with `maxAllowedSurcharge` below the target's listed surcharge → rejected by inventory; at the ceiling → allowed; new-seat reservation compensated (cancelled) when the local check-in update throws |
| Payment/Invoice aggregates (round 5) | payment + invoice carry `baseFareTotal`/`seatSurchargeTotal` matching the event sums; `fareBreakdown` string byte-identical to pre-branch format; legacy rows show null aggregates |
| End-to-end (gateway, seeded flight) | (a) no seat → free middle economy; (b) choose 1A on the 777 → First base fare + window surcharge, persisted; (c) FIRST on an A320 flight → rejected |
| Regression | full reactor `mvn clean verify` green; existing booking/refund tests updated for the now-optional seat + persisted breakdown |
