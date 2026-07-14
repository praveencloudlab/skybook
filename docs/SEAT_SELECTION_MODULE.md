# 💺 SkyBook Seat Selection & Seat Pricing — Design

---

## Project Information

| | |
|---|---|
| **Scope** | Free auto-assignment + paid seat selection; per-seat surcharge pricing by attribute; cabin-aware assignment; enforced at booking **and** check-in; original fare breakdown persisted per passenger |
| **Branch** | `feature/seat-selection` |
| **Status** | Frozen after three review rounds. R1: persisted breakdown, atomic inventory auto-hold, deferred paid check-in upgrades, availability-not-fares, listed-vs-charged, exit-row≠extra-legroom, refund policy. R2: explicit migration not ddl-auto, per-flight lock, USD-only, chargedSeatAssignmentMode rename, exhaustion + cabin-relative front rows. **R3 (this pass): auto-assignment moves from the createBooking call site into BookingFacade's draft→hold→finalize flow (seat_number nullable in draft); one shared pessimistic flight-lock for manual AND auto holds; SeatPricingPolicy takes CabinPricingContext + deterministic ordering tuple; Flyway (not a scripts/seed script) as the real migration mechanism; entitlement-ceiling check-in rule.** Implementation per §14 (step 1 re-opened for Flyway). |

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

- **Free auto-assignment** — the empty `AutoSeatAssignmentStrategy` slot the
  `SeatAssignmentStrategy` interface was designed for. **Selection *and* hold
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
                                                     seat_number NULL, fare = baseFare only]
  → for each passenger: inventory hold (manual OR atomic auto)  [outside any booking tx]
        → collect SeatAssignmentResult{ seatNumber, listedSurcharge,
                                        chargedSurcharge, mode }
  → bookingService.finalizeSeatAssignments(...)      [tx commit: seat_number, seatSurcharge,
                                                     chargedSeatAssignmentMode, fare = base + charged]
  → publish BookingCreated
On any failure: release already-held seats → cancel the draft booking → rethrow.
```

- **`booking_passengers.seat_number` becomes nullable** for the draft stage (it is
  `nullable=false` today) — a column change in the same migration as §8.
- The `SeatAssignmentStrategy` interface still has value as the manual-vs-auto
  *decision*, but the seat is no longer resolved inside `createBooking`; resolution
  moves to the facade's hold step where the IDs and the inventory result exist.

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
  passenger's booked `travelClass` (§7).
- Held via the existing `/hold` path, whose response now carries
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

- **Schema change is a real Flyway migration, run automatically at startup (review
  round 3, correction #4).** `ddl-auto` never runs data transforms and rejects
  adding `NOT NULL` to a populated table; a hand-run script under `scripts/seed`
  has **no execution mechanism** in CI / Compose / Kubernetes — a new NOT-NULL
  entity against a populated DB with an un-run backfill would fail on startup. So
  this branch **introduces Flyway in booking-service** (the dependency already
  exists in auth-service; Flyway runs *before* Hibernate, then `ddl-auto: update`
  sees a matching schema and no-ops). Migration
  `V1__add_booking_passenger_fare_breakdown.sql`, idempotent, `baseline-on-migrate:
  true` (the existing ddl-auto schema becomes the baseline):
  `ALTER ADD (nullable) + DROP NOT NULL on seat_number → UPDATE backfill
  (baseFare=fare, seatSurcharge=0, chargedSeatAssignmentMode='MANUAL',
  currency='USD') → ALTER SET NOT NULL on the four new columns`. The interim
  `scripts/seed/booking_breakdown_backfill.sql` from step 1 is superseded by this
  and removed. (Flyway fleet-wide is a natural follow-up branch, §12.)
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
- **Breakdown preservation:** the booking/payment events and the invoice must
  carry the seat-fee component, not a single unexplained total. The persisted
  breakdown (§8) is the source; `BookingEvent`, the payment fare snapshot, and the
  invoice line items surface `baseFare` + `seatSurcharge` separately. (This is the
  one place existing event/invoice DTOs gain fields — additive.)

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
| Booking response / events / invoice | gain `baseFare` + `seatSurcharge` + `chargedSeatAssignmentMode` breakdown alongside the all-in `fare` | booking |

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

- **Backfill of existing bookings** — the new `BookingPassenger` columns are added
  by an **explicit SQL migration** (not `ddl-auto`, §8), backfilling
  `seatSurcharge=0`, `chargedSeatAssignmentMode='MANUAL'`, `baseFare=fare`,
  `currency='USD'`, then setting `NOT NULL`. Shipped in
  `scripts/seed/booking_breakdown_backfill.sql`. The lack of a migration tool is
  the standing argument for adopting **Flyway** (auth-service already has the
  dependency with zero migrations) — deferred to its own branch.
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

1. **Persisted breakdown via Flyway** (§8) — introduce Flyway in booking-service,
   `V1__add_booking_passenger_fare_breakdown.sql` (adds the four columns, drops
   NOT NULL on `seat_number` for the draft stage, backfills, sets NOT NULL);
   `baseline-on-migrate: true`. **Supersedes the interim
   `scripts/seed/booking_breakdown_backfill.sql` shipped in the first pass — remove
   it.** Verify existing bookings still read/refund; migration runs in
   Compose/CI/K8s automatically.
2. **`SeatPricingPolicy` in inventory** (§4) — `(seat, CabinPricingContext)` →
   listed-surcharge, `max` composition, config-driven; surface `listedSurcharge`
   on the seat map; unit-tested (highest-tier + cabin-relative front-row).
3. **Atomic auto-hold + shared flight lock** (§5) — `findByFlightIdForUpdate`
   (`PESSIMISTIC_WRITE`) adopted by **both** `holdSeat` and the new
   `POST /holds/auto`; deterministic ordering tuple; concurrency test proving
   AUTO↔MANUAL and AUTO↔AUTO both serialize (different seats, no rollback-only,
   counts correct).
4. **Draft → hold → finalize flow in `BookingFacade`** (§5.1) — `createDraftBooking`
   (seat null) → per-passenger inventory hold (manual/auto) → `finalizeSeatAssignments`;
   compensation on failure. Booking omit-seat → free auto seat end-to-end.
5. **Manual-selection surcharge + fare assembly** (§3/§6/§8) — finalize persists
   base + charged surcharge + mode; response breakdown.
6. **Cabin availability + booking quote** (§7/§11) — inventory `/cabins`
   (availability only) + booking `/quote` (assembles fares); clear "no such cabin"
   error.
7. **Check-in contained-v1 rule** (§9) — allow ≤-surcharge changes with cabin
   check, reject upgrades with the Manage-Booking message.
8. **Refund/invoice breakdown preservation** (§10) — events/invoice carry the
   seat-fee component; confirm refund uses the persisted total.
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
| Concurrency | two atomic auto-assigns racing the last seat → one wins, one gets the next preference (no double-book) |
| End-to-end (gateway, seeded flight) | (a) no seat → free middle economy; (b) choose 1A on the 777 → First base fare + window surcharge, persisted; (c) FIRST on an A320 flight → rejected |
| Regression | full reactor `mvn clean verify` green; existing booking/refund tests updated for the now-optional seat + persisted breakdown |
