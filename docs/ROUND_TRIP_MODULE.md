# Round Trip — Single-PNR Multi-Segment Bookings (Design)

Status: **DESIGN — for review, not yet implemented.**
The shipped interim is two separate tickets per direction (commit d635d5f);
existing bookings become single-segment bookings under this design unchanged.

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

`BookingPassenger` becomes **per passenger per segment**: add
`segment_id` FK. Everything the row already holds — fare breakdown, seat,
check-in mirror, cancelled — is genuinely per-direction, so the existing
machinery transfers without semantic change:

- **checkin-service needs no schema change**: its unique
  `booking_passenger_id` now identifies a passenger-on-a-segment, giving
  per-direction check-in and boarding passes for free.
- `totalFare = Σ fare over ALL rows` — the standing invariant holds.
- `PARTIALLY_CANCELLED` derivation (any active + any cancelled row) holds.
- `bookings.flight_id` is kept, documented as segment 0's flight
  (denormalized for the reader paths that only need "the trip's flight").

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

`BookingEventPassenger` gains the per-row flight fields (flightId,
flightNumber, origin, destination, departureTime, arrivalTime) instead of
relying on the event's single top-level flight; the top-level flight stays
(segment 0) for consumer compatibility.

- checkin-service: iterate passenger entries, snapshot each entry's OWN
  flight fields → per-segment CheckIn rows. Consumer change only.
- notification-service: confirmation email + e-ticket render one coupon per
  segment (itinerary table already row-per-segment shaped).
- payment-service: unchanged — one payment, combined amount.
- Old events (no per-entry flight fields) must still parse: consumers fall
  back to the top-level flight when entry fields are null.

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
3. Event enrichment + checkin/notification consumer updates (+ old-event
   fallback tests).
4. Cancellation matrix: segment cancel endpoint + guardian/agent guards.
5. Frontend: single-call payment, confirmation, detail segments, cancel-return.
6. Live e2e: round trip book→pay→check in outbound→cancel return→refund.

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
