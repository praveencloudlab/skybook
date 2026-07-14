# 💺 SkyBook Seat Selection & Seat Pricing — Design

---

## Project Information

| | |
|---|---|
| **Scope** | Free auto-assignment + paid seat selection; per-seat surcharge pricing by attribute; cabin-aware assignment; enforced at booking **and** check-in |
| **Branch** | `feature/seat-selection` |
| **Status** | Design draft — under review, not yet frozen |

Goal: model seats the way real airlines do. A passenger who doesn't care gets a
low-demand seat **auto-assigned for free**; a passenger who wants a specific
seat — window, extra legroom/exit row, or a front-of-cabin spot — **pays a
surcharge** for it, on top of the cabin base fare. Cabin classes
(Economy / Premium Economy / Business / First) already exist in the data;
what's missing is the *assignment algorithm*, the *surcharge pricing*, and the
*free-vs-paid* distinction.

---

# Table of Contents

1. [Overview](#1-overview)
2. [Load-Bearing Findings](#2-load-bearing-findings)
3. [The Pricing Model](#3-the-pricing-model)
4. [Seat Surcharge — Where It's Computed](#4-seat-surcharge--where-its-computed)
5. [Auto-Assignment (Free)](#5-auto-assignment-free)
6. [Manual Selection (Paid)](#6-manual-selection-paid)
7. [Cabin-Aware Assignment](#7-cabin-aware-assignment)
8. [Fare Assembly](#8-fare-assembly)
9. [Check-In Seat Changes](#9-check-in-seat-changes)
10. [API Changes](#10-api-changes)
11. [Deferred / Out of Scope](#11-deferred--out-of-scope)
12. [Known Risks / Open Questions](#12-known-risks--open-questions)
13. [Build Order](#13-build-order)
14. [Testing Plan](#14-testing-plan)

---

# 1. Overview

Three capabilities, all landing on seams the codebase already built:

- **Free auto-assignment** — the empty `AutoSeatAssignmentStrategy` slot the
  `SeatAssignmentStrategy` interface was designed for (its Javadoc: *"a real
  assignment algorithm is premature before there's an actual seat map to pick
  from"* — which now exists, seeded).
- **Per-seat surcharge pricing** — a config-driven `SeatPricingPolicy` in
  inventory-service, surfaced on the existing `/seat-map` and `/hold`
  responses; booking adds it to the cabin base fare `FareCalculator` already
  produces.
- **Free-vs-paid rule** — auto-assigned seat → surcharge 0; a *chosen* seat →
  its attribute surcharge.

No new microservice. Seat *pricing* is deliberately kept as a small,
config-driven policy inside inventory-service (which owns seats), not a
premature full Pricing Service — that stays Phase 2 (dynamic pricing, taxes,
promotions), and this design leaves a clean seam for it to take over surcharge
computation later.

---

# 2. Load-Bearing Findings

Confirmed by reading the code, not assumed:

1. **The auto-assignment seam already exists and is deliberately empty.**
   `SeatAssignmentStrategy` is an interface; `ManualSeatAssignmentStrategy` is
   the v1 impl that rejects a blank seat with *"automatic seat assignment isn't
   implemented yet"*. `BookingServiceImpl` already depends on the interface
   (`seatAssignmentStrategy.resolveSeatNumber(...)`) — so adding an auto impl is
   a new `@Component` + a selection rule, **not** a refactor of the call site.
2. **Seat categories and attributes are already fully modeled.** `AircraftSeat`
   carries `seatType` (ECONOMY / PREMIUM_ECONOMY / BUSINESS / FIRST), `position`
   (WINDOW / MIDDLE / AISLE), `exitRow` (boolean), and `rowNumber`. Every input
   the surcharge model needs is present on the seat — no schema change to
   `aircraft_seats` required for pricing *inputs*.
3. **"Not every flight has every cabin" is already true.** A flight's sellable
   seats come from its aircraft's seat map. The seeded A320 has only Business +
   Economy; the 777 has all four cabins. So a request for a FIRST seat on an
   A320 flight already has no seat to satisfy it — the cabin-availability rule
   the user asked for falls out of the existing model; it just needs a clear
   error instead of a confusing one (§7).
4. **Base fare per cabin already exists** — `FareCalculator` maps TravelClass →
   base fare (Eco 100 / Premium 180 / Business 350 / First 700) × a FareType
   multiplier, stored per passenger on `BookingPassenger.fare`. Seat surcharge
   is **additive** to this, so `calculateFare`'s contract barely changes (§8).
5. **Inventory already exposes a seat map and holds specific seats.** Endpoints
   `/seat-map`, `/seats`, `/seats/status/{status}` exist, and booking already
   calls `/hold` with a specific seat number. So "show me the seats and their
   prices" and "hold this seat" both already have homes — the surcharge just
   needs to ride along on those responses.
6. **Check-in seat change already exists.** `PATCH /api/checkins/{id}/seat`
   (`changeSeat`) is implemented. The free-vs-paid rule must apply here too, so
   a passenger can't dodge the window surcharge by booking a free auto seat and
   swapping to a window at check-in for nothing (§9).
7. **Double-booking is already guarded** — a `(flightId, seatNumber)` uniqueness
   constraint plus inventory's hold/reserve counts. Assignment logic does *not*
   need to re-implement availability locking; it selects from what inventory
   reports available and lets the existing constraint be the backstop.

---

# 3. The Pricing Model

Two independent layers, matching how airlines actually bill:

```
   passenger fare  =  CABIN BASE FARE            +  SEAT SURCHARGE
                      (TravelClass × FareType)      (attribute add-on, only
                      FareCalculator, exists         if the seat was CHOSEN;
                      today)                         0 if auto-assigned)
```

- **Cabin base fare** is the ticket class — Economy vs Business vs First. Booking
  a Business ticket costs the Business base fare regardless of *which* Business
  seat you sit in. Unchanged from today.
- **Seat surcharge** is a *within-cabin* add-on for a more desirable seat, and
  it is **only charged when the passenger explicitly picks the seat**. Auto-
  assignment is always free. This is exactly the user's rule: *"assigning a seat
  automatically is not a paid service … if a passenger chooses himself, then it's
  a paid service."*

Surcharge by attribute (config-driven, starting values — tuned later, same
"reasoned defaults" posture as every other module):

| Seat attribute | Example surcharge (on top of cabin fare) |
|---|---|
| Standard middle economy | £0 (the free auto-assign pool) |
| Aisle (standard) | £8 |
| Window (standard) | £12 |
| Extra legroom / exit row | £30 |
| Front-of-cabin (first N rows of the cabin) | £15 |

Within a premium cabin (Business/First), the base fare already reflects the
cabin; surcharges there are smaller/optional (a Business window vs aisle), and
the same attribute table applies on top of the higher base. Exact numbers are
config, not code (§4).

---

# 4. Seat Surcharge — Where It's Computed

**Decision: a config-driven `SeatPricingPolicy` inside inventory-service**, not a
new Pricing Service and not hard-coded in booking.

- Inventory owns the seats and their attributes (§2.2), so it's the natural home
  for "what does *this* seat cost extra". The policy is a pure function of seat
  attributes (`seatType`, `position`, `exitRow`, `rowNumber` relative to its
  cabin), driven by values in `application.yml` (`inventory.seat-pricing.*`).
- The surcharge is **surfaced on responses inventory already returns**: each seat
  in the `/seat-map` gains a `surcharge` field, and the `/hold` response returns
  the held seat's surcharge so booking can bill it.
- **Why not a full Pricing Service now:** the roadmap's Phase-2 Pricing Service is
  a much bigger scope (dynamic pricing, fare rules, taxes, promotions, coupons).
  Seat surcharge is a small, static, attribute-driven calculation; standing up a
  whole service for it is premature. The `SeatPricingPolicy` is a single class
  behind a clear interface — when the Pricing Service arrives, it can implement
  that same interface (or inventory can delegate to it), and nothing in booking
  changes. Documented as the deliberate seam it is.
- **No new persisted price column on `aircraft_seats`.** Surcharge is *derived*
  from attributes at read time, not stored — so re-pricing is a config change,
  not a data migration. (If per-seat manual overrides are ever needed, that's a
  nullable `surcharge_override` column later — deferred, §11.)

---

# 5. Auto-Assignment (Free)

New `AutoSeatAssignmentStrategy implements SeatAssignmentStrategy`, selected when
the passenger supplies **no** seat number.

- Picks the **lowest-demand available seat in the passenger's booked cabin**:
  prefer standard middle → aisle → window, avoid exit-row/extra-legroom and
  front rows (those are revenue seats, kept for paying selectors). The goal is
  literally the user's *"any seat which is not in high demand can be assigned"*.
- Queries inventory for available seats of the passenger's `travelClass` on the
  flight (extends the existing `/seats/status/AVAILABLE`-style view with a cabin
  filter), applies the preference ordering, takes the first.
- **Always free** — an auto-assigned seat carries surcharge 0 regardless of which
  physical seat the algorithm lands on (even if only window seats remain, an
  auto-assign doesn't bill for it — you didn't choose it).
- **Strategy selection:** rather than two competing `@Component` beans (ambiguous
  injection), `SeatAssignmentStrategy` becomes a small dispatcher — if
  `requestedSeatNumber` is blank → auto path, else → manual/validate path. Keeps
  `BookingServiceImpl`'s single call site untouched.

---

# 6. Manual Selection (Paid)

When the passenger **does** supply a seat number:

- The seat is validated (exists on the aircraft, is AVAILABLE, and its `seatType`
  matches the passenger's booked `travelClass` — §7).
- Its surcharge (from inventory's `SeatPricingPolicy`) is added to the passenger's
  fare (§8).
- Held in inventory via the existing `/hold` path; the `(flightId, seatNumber)`
  uniqueness constraint remains the double-booking backstop.

A chosen *standard middle economy* seat has surcharge £0 — so "I want to pick my
own seat but I'm not paying for a premium one" is honored: selection of a
zero-surcharge seat is free, selection of a premium seat is billed. This matches
real airline behavior where standard seat selection is often free and only
preferred/extra-legroom seats carry a fee.

---

# 7. Cabin-Aware Assignment

- A passenger's `travelClass` determines which seats they may occupy: an ECONOMY
  ticket → an ECONOMY seat, BUSINESS → BUSINESS, etc. Enforced on both auto and
  manual paths.
- **A flight only offers the cabins its aircraft has.** Booking FIRST on an A320
  flight (no First seats) fails with a clear *"this flight has no First-class
  seats"* rather than a generic "seat unavailable". This is the user's *"not
  every flight has business/first/premium; but any flight with these features can
  be booked into them"* — already true structurally (§2.3), this branch just
  makes the error message honest and adds a **cabin-availability lookup** so a
  client can ask "which cabins does this flight sell?" before booking.

---

# 8. Fare Assembly

- `FareCalculator.calculateFare(travelClass, fareType)` stays as the **cabin base
  fare**. A new overload / wrapper adds the seat surcharge:
  `totalPassengerFare = calculateFare(class, fareType) + seatSurcharge`.
- The surcharge value comes from inventory (returned by the hold/seat-map call),
  so booking doesn't duplicate the pricing rules — it just adds the number
  inventory gives it. Single source of truth for seat pricing stays in inventory.
- `BookingPassenger.fare` continues to store the **all-in per-passenger fare**
  (base + surcharge), so payment, refunds, and invoicing need no changes — they
  already read `fare`. The booking response gains a breakdown
  (`baseFare` + `seatSurcharge`) for transparency, but the billed total is the
  same field as today.

---

# 9. Check-In Seat Changes

`PATCH /api/checkins/{id}/seat` already exists. The free-vs-paid rule must hold
here too, or a passenger games it (book a free auto seat, swap to a window at
check-in for free):

- Changing to a seat with a **higher** surcharge than the current one bills the
  **difference** (a fare adjustment on the booking/payment).
- Changing to an equal-or-lower seat is free (no refund of surcharge in v1 —
  documented simplification, §11).
- **Open question flagged (§12):** payment adjustment at check-in touches the
  payment-service flow. The simplest v1 is to record the surcharge delta on the
  booking and settle it as an additional charge; a fuller "authorize the delta at
  check-in" flow may be deferred. This is the one genuinely cross-service wrinkle
  and the design review should weigh in on how far to take it in this branch.

---

# 10. API Changes

Additive only — no breaking changes to existing request shapes:

| Endpoint | Change |
|---|---|
| `POST /api/bookings` | `seatNumber` per passenger becomes **truly optional** — omit it → free auto-assign; supply it → paid selection. (Today it's "optional but effectively required".) |
| `GET /api/inventory/flight/{flightId}/seat-map` (inventory) | each seat gains `surcharge` + `available` so a client can render a seat map with prices |
| `GET /api/inventory/flight/{flightId}/cabins` (new) | which cabins this flight sells + base fare + seat-count — lets a UI show "Economy from £100, Business from £350" |
| `PATCH /api/checkins/{id}/seat` | applies the surcharge-difference rule (§9) |
| Booking response | passenger gains `baseFare` + `seatSurcharge` breakdown alongside the existing all-in `fare` |

---

# 11. Deferred / Out of Scope

- **Full Pricing Service** (dynamic pricing, fare rules, taxes, promotions,
  coupons) — Phase 2; this branch is seat surcharge only, behind an interface the
  Pricing Service can later implement (§4).
- **Per-seat manual price overrides** (a stored `surcharge_override`) — attributes
  drive price for now; overrides are a later nullable column.
- **Refunding a surcharge** when downgrading a seat at check-in — v1 doesn't
  refund the difference (§9); only upgrades are billed.
- **Seat-map holds/timeouts for in-progress selection** (holding a seat while a
  user browses) — the existing hold-on-book flow is enough; interactive-selection
  holds are a frontend-era concern.
- **Group/family seat-adjacency preferences** in auto-assign — v1 auto-assign is
  per-passenger independent; keeping a family together is a nice-to-have later.

---

# 12. Known Risks / Open Questions

- **Check-in surcharge settlement is the one cross-service wrinkle** (§9) — how
  far to take payment adjustment at check-in is the main thing for review to
  decide; everything else is contained to booking + inventory.
- **Surcharge numbers are pre-tuning defaults** — same honesty clause as every
  module; they're config, changeable without a deploy.
- **Auto-assign "low demand" ordering is a heuristic** — middle-first is a
  reasonable default but not load-tested; it's a config-ordered preference list,
  not hard-coded.
- **Race on the last free seat** — two concurrent auto-assigns could pick the same
  seat; the existing `(flightId, seatNumber)` unique constraint + hold failure is
  the backstop (one retries with the next preference). Needs a test
  (concurrency), same pattern as checkin's existing `CheckInConcurrencyTest`.

---

# 13. Build Order

1. **`SeatPricingPolicy` in inventory** (§4) — pure attribute→surcharge function,
   config-driven, unit-tested in isolation; surface `surcharge` on the seat-map
   response.
2. **Cabin-availability view** (§7) — `/cabins` endpoint + the "no such cabin on
   this flight" clear error.
3. **`AutoSeatAssignmentStrategy`** (§5) — dispatcher + free auto-pick; unit tests
   for preference ordering and the always-free rule.
4. **Fare assembly** (§8) — booking adds inventory's surcharge to the cabin base
   fare; booking response breakdown.
5. **Manual-selection surcharge** (§6) end-to-end through the gateway (choose a
   window seat → fare reflects the surcharge; omit a seat → free auto seat).
6. **Check-in seat-change surcharge** (§9) — the difference-billing rule, to the
   agreed v1 depth.
7. **Design doc → implemented + Implementation Notes**, house pattern.

---

# 14. Testing Plan

| Layer | What's tested |
|---|---|
| Seat pricing | `SeatPricingPolicy` — each attribute combination maps to the configured surcharge; middle economy = 0 |
| Auto-assign | no seat supplied → a free, in-cabin, low-demand seat is chosen; surcharge 0; respects the preference ordering |
| Manual paid | window/exit-row seat supplied → fare = base + that seat's surcharge |
| Cabin rules | FIRST requested on an A320 flight → clear "no First cabin" error; ECONOMY ticket can't take a BUSINESS seat |
| Fare assembly | `BookingPassenger.fare` = base + surcharge; response breakdown correct; payment/refund unaffected (read the same `fare`) |
| Check-in change | upgrade seat → surcharge difference billed; same/lower → free |
| Concurrency | two auto-assigns racing the last seat → one wins, one falls through to the next preference (no double-book) |
| End-to-end | through the gateway on a seeded flight: (a) book with no seat → free middle economy; (b) book choosing 1A on the 777 → First-cabin fare + window surcharge; (c) book FIRST on an A320 flight → rejected |
| Regression | full reactor `mvn clean verify` green; existing booking tests updated for the now-optional seat |
