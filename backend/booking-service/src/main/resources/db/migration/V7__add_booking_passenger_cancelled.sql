-- Passenger-level cancellation (business rules: passenger cancel must NOT cancel
-- the booking; the booking is CANCELLED only when every passenger is cancelled).
-- A per-passenger flag lets a booking outlive the cancellation of some of its
-- travellers - the remaining passengers keep their seats, tickets and services.

ALTER TABLE booking_passengers
    ADD COLUMN IF NOT EXISTS cancelled boolean NOT NULL DEFAULT false;
