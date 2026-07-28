-- Widen the booking_status CHECK constraint to admit PARTIALLY_CANCELLED, the
-- derived status a booking takes when some (but not all) of its passengers are
-- cancelled (passenger-cancellation business rules 9-11). The V1 baseline
-- constraint predates DRAFT and PARTIALLY_CANCELLED; DRAFT was already added to
-- the live column, so re-state the full set here.

ALTER TABLE bookings DROP CONSTRAINT IF EXISTS bookings_booking_status_check;

ALTER TABLE bookings ADD CONSTRAINT bookings_booking_status_check
    CHECK (booking_status IN ('DRAFT', 'CREATED', 'CONFIRMED', 'PARTIALLY_CANCELLED', 'CANCELLED', 'COMPLETED'));
