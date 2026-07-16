-- Remove uk_flight_seat (SEAT_SELECTION_MODULE.md §2.6, review round 7).
--
-- The constraint is unconditional over (flight_id, seat_number), but a
-- cancelled booking keeps its historical seat row - so once any booking on a
-- flight is cancelled, that seat can never be booked again on that flight
-- even though inventory correctly releases it (cancel -> rebook-same-seat
-- was broken). Live seat exclusivity is inventory-service's job (shared
-- flight lock + active holds/reservations); booking keeps seat rows as
-- historical snapshot and audit/display only.
--
-- No booking-side replacement: a partial unique index can't express "unique
-- only for active bookings" because booking_status lives on bookings, not
-- booking_passengers - and a cross-table constraint would duplicate what
-- inventory already owns.

ALTER TABLE booking_passengers
    DROP CONSTRAINT IF EXISTS uk_flight_seat;
