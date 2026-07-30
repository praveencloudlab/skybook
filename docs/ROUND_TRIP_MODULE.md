# Round Trip — Single-PNR Multi-Segment Bookings

Status: **IMPLEMENTED (build-order steps 1–7, live-verified 2026-07-30).**
Step 8 (drop `bookings.flight_id` + the deprecated flat event fields) is BY
DESIGN a follow-up release — the deprecated mirrors shipped in THIS release
ARE the one-release compatibility window, so removing them in the same
release would defeat it. See Implementation Notes at the end.

## 1. Goal

One booking reference for a round trip sold by SkyBook itself — one PNR, one
payment, one confirmation, with outbound and return as *segments* inside it
(one e-ticket coupon per segment, per-segment check-in), the way a single
carrier actually tickets (user's reference: BA LON–HYD round trip = one ref).

Non-goals: multi-city (>2 segments) beyond keeping the model open to it;
mixed-cabin per segment (v1 keeps one cabin+fare family per booking);
per-direction extra-bag counts (v1 applies one count to both segments).

## 2. Current state (traced)

- `Booking.flightId` — ONE flight per booking. `BookingPassenger` rows carry
  `flightId`, the immutable fare breakdown (baseFare, seatSurcharge,
  extraBags, baggageFee, fare), seat, `checkInStatus` mirror, `cancelled`.
- Create saga: draft (fares priced per departure date) → per-passenger seat
  holds at inventory with compensation → finalize (one tx: money fields,
  payment snapshot, DRAFT→CREATED) → BookingEvent (flight-enriched).
- checkin-service creates one CheckIn per `bookingPassengerId`
  (unique) with flight snapshot from the event; CheckInEvent mirrors state
  back onto `BookingPassenger.checkInStatus`.
- Cancellation: whole booking, or per passenger (guardian rule;
  PARTIALLY_CANCELLED derived from row states; refund = stored fares; seats
  released per row).

## 3. Domain model

New table `booking_segments`:

| column | notes |
| --- | --- |
| id | PK |
| booking_id | FK |
| segment_index | 0 = outbound, 1 = return |
| flight_id | flight-service id (no route snapshot — events enrich, as today) |

Note on "flight instance": SkyBook's `flights` rows ARE dated instances —
each row is a flight number on a concrete departure datetime (there is no
separate route entity). `segment.flight_id` therefore already identifies
"BA178 on 29 Jul 10:15", not a route; no rename needed.

`BookingPassenger` becomes **per passenger per segment**: add
`segment_id` FK. Everything the row already holds — fare breakdown, seat,
check-in mirror, cancelled — is genuinely per-direction, so the existing
machinery transfers without semantic change:

- **checkin-service needs no schema change**: its unique
  `booking_passenger_id` now identifies a passenger-on-a-segment, giving
  per-direction check-in and boarding passes for free.
- `totalFare = Σ fare over ALL rows` — the standing invariant holds.
- `PARTIALLY_CANCELLED` derivation (any active + any cancelled row) holds.
- `bookings.flight_id` is kept **for one release only** as segment 0's
  flight, marked deprecated; once every reader (responses, events, rebook,
  admin queries) goes through segments, a follow-up migration drops it.
  Long-term shape is strictly Booking → BookingSegment → BookingPassenger.

### Tickets and coupons (IATA-style)

New tables, issued when the booking reaches CONFIRMED:

- `tickets` — one per (booking × passenger): id, booking_id, ticket number
  (13-digit, `125-` prefix style), status (ISSUED/VOID/REFUNDED), issued_at.
- `ticket_coupons` — one per segment of that passenger's journey, 1:1 with
  the per-segment `booking_passengers` row: id, ticket_id,
  booking_passenger_id (unique), coupon_number, status
  (OPEN/CHECKED_IN/FLOWN/CANCELLED/REFUNDED).

Coupon status follows the row's lifecycle (check-in mirror, cancellation,
departure passed). The e-ticket document renders ticket number + one coupon
line per segment; a later "Coupon 2 CANCELLED / Coupon 1 FLOWN" state is
first-class. Boarding passes stay where they live today — generated off the
CheckIn record in checkin-service (Ticket → CheckIn → BoardingPass), never
attached to BookingPassenger directly.

### Derived segment status

`BookingResponse.segments[]` gains a **computed** (never stored) status —
CANCELLED (all rows cancelled) / FLOWN (departure passed) / CHECKED_IN (any
active row checked in) / UPCOMING — same derivation philosophy as booking
status, so the UI shows "Outbound — Completed / Return — Upcoming" without
client-side date math.

## 4. API contract

`CreateBookingRequest` gains `returnFlightId?: Long`. Passengers list stays
one entry per traveller: the server fans each entry out to one row per
segment (seat pick applies to segment 0 only in v1 — return seats
auto-assign, matching the shipped UI; `extraBags` applies per segment).

`BookingResponse` gains `segments: [{segmentIndex, flightId}]`; passenger
rows gain `segmentIndex`. Existing single-flight consumers keep working:
segment 0 mirrors today's shape.

New endpoint: `POST /bookings/{id}/segments/{segmentIndex}/cancel`
(cancel just the return — see §7).

## 5. The saga across segments

Draft: create segments; price each row with `FareCalculator` against **its
segment's departure date** (demand curve per direction — matches the
combined quote the fare page already shows); Flexi/Premium seat waiver per
row as today.

Holds: loop segments × passengers against each segment's flight inventory,
one compensation list spanning both flights — any failure releases every
hold taken on **either** flight and cancels the draft. All-or-nothing.

Finalize: `validateCompleteCoverage` extends to require every
(passenger × segment) row covered exactly once. ONE payment snapshot for
the combined total. One CREATED→CONFIRMED lifecycle, as today.

## 6. Events and cross-service impact

The event nests passengers under segments instead of flattening flight
fields onto every passenger entry (three passengers would otherwise repeat
identical flight data three times):

```
BookingEvent
├── (top-level flight + flat passengers — DEPRECATED, segment 0 mirror,
│    kept one release for old consumers and replayed old events)
└── segments[]: {segmentIndex, flightId, flightNumber, origin,
                 destination, departureTime, arrivalTime,
                 passengers[]: per-row entries (seat, fare, ids…)}
```

- checkin-service: iterate segments × their passenger entries, snapshot
  the SEGMENT's flight fields → per-segment CheckIn rows. Consumer change
  only.
- notification-service: confirmation email + e-ticket render one coupon per
  segment (itinerary table already row-per-segment shaped).
- payment-service: unchanged — one payment, combined amount.
- Old events (no `segments` array) must still parse: consumers fall back
  to the top-level flight + flat passenger list when `segments` is null.

## 7. Cancellation matrix (v1 rules)

| action | effect | refund | guards |
| --- | --- | --- | --- |
| Cancel booking | all rows | all stored fares | none checked in |
| Cancel passenger | their rows on **all** segments | their fares | not checked in on any segment; guardian rule per segment on remainder |
| Cancel segment (new) | all active rows of that segment | those fares | segment not flown; nobody checked in on it; **only the return (index ≥ 1)** may be cancelled alone — dropping the outbound but flying the return is a no-show trap airlines reject |

State stays derived: any active + any cancelled row ⇒ PARTIALLY_CANCELLED;
all cancelled ⇒ CANCELLED.

## 8. Migration (V10) and compatibility

1. Create `booking_segments`; backfill one segment (index 0) per existing
   booking from `bookings.flight_id`.
2. Add `booking_passengers.segment_id`, backfill to that segment, NOT NULL.
3. No data loss, no id changes; every existing booking is a valid
   single-segment booking. Two-ticket round trips already sold stay two
   independent bookings — never merged retroactively.

## 9. Frontend

- Payment: ONE `create` call with `returnFlightId`; the two-booking loop
  goes away. Confirmation shows one PNR with both segments.
- My trips / detail: segment cards (outbound + return) under one header;
  per-segment check-in sections (already per-CheckIn-record, so mostly
  falls out); "Cancel return" action per §7.
- Modify/rebook dialog: operates per segment (change the return alone).

## 10. Build order

1. V10 migration + entities + mapper/response (backward-compatible reads).
2. Saga: segment-aware draft/holds/finalize + unit tests (compensation
   across two flights is the critical test).
3. Tickets + coupons: tables, issuance at CONFIRMED, coupon lifecycle
   hooks, e-ticket rendering.
4. Event enrichment (nested segments) + checkin/notification consumer
   updates (+ old-event fallback tests).
5. Cancellation matrix: segment cancel endpoint + guardian/agent guards +
   per-segment rebook (Premium both-way date change).
6. Frontend: single-call payment, confirmation, detail segments with
   derived status, cancel-return, per-segment Modify.
7. Live e2e: round trip book→pay→check in outbound→cancel return→refund,
   verifying coupon states end up FLOWN/CANCELLED correctly.
8. Follow-up release: drop `bookings.flight_id` + the deprecated flat
   event fields once all readers are on segments.

## 11. Risks / open questions

- Event-shape drift is the sharpest edge: three consumers parse
  BookingEvent; the null-fallback must be tested against replayed old events.
- Inventory holds across two flights double the compensation surface — the
  saga test matrix must cover "second flight's first hold fails".
- Rebooking a single segment interacts with the Modify dialog's
  cancel+rebook mechanics; it cannot be deferred, since the Premium
  both-way date-change decision below depends on it.
- ~~Open~~ **Decided (user, 2026-07-30): Premium's free date change applies
  to BOTH directions** — outbound and return are each independently
  changeable online (fare difference only), guarded the usual way (segment
  not flown, nobody checked in on it). Mechanically each change is a
  segment rebook: cancel that segment's rows, recreate them on the new
  flight, refund/charge the fare difference.

## 12. Implementation Notes (2026-07-30)

Steps 1–7 landed on feature/frontend as one commit per step. Deviations
and decisions made during the build:

- **Row correlation**: draft rows are persisted segment-major (all outbound
  rows, then all return rows) and `Booking.passengers` gained
  `@OrderBy("id ASC")` so the facade's row `i` ↔ request detail
  `i % travellerCount` correlation survives a reload.
- **Ticket numbers are deterministic**, not random: `125` + 8-digit booking
  id + 2-digit traveller index. A redelivered payment event re-derives the
  identical number, making issuance idempotent with no uniqueness table.
- **Premium seat waiver makes rebook money-simple**: only PREMIUM rows may
  segment-rebook, and Premium seat picks charge 0 — so a rebook never moves
  seat money, only the base-fare difference (totalFare + payment snapshot
  adjust; BookingHistory records the delta; the processor is simulated).
- **Exchanged rows ride the refreshed CONFIRMED event as CLOSED**:
  checkin-service cancels their CheckIns and creates fresh ones for the
  replacement rows — per-direction check-in needed zero checkin schema
  change, exactly as designed (§3).
- **Segment status FLOWN** is derived by the frontend from the departure
  time it already fetches; the server derives CANCELLED/CHECKED_IN/UPCOMING.
- **Live e2e (all 25 checks passed)**: booking SB4H2F on the compose stack —
  one booking, two segments, rows on their own flights, both legs seated,
  totalFare = Σ rows = payment amount (475.00); CONFIRMED issued ticket
  125-0000018201 with coupons C1/C2 OPEN; Premium date change moved the
  return (C2 CANCELLED, C3 OPEN, total → 465.00, old return CheckIn
  CANCELLED, new one created); outbound check-in mirrored C1 → CHECKED_IN
  and segment 0 → CHECKED_IN; cancel-return refunded 205.00, booking →
  PARTIALLY_CANCELLED, final coupons C1 CHECKED_IN / C2 CANCELLED /
  C3 REFUNDED, segment 1 → CANCELLED; cancelling segment 0 alone rejected.
  V10+V11 migrated the live database (existing bookings backfilled as
  single-segment) with zero errors.
