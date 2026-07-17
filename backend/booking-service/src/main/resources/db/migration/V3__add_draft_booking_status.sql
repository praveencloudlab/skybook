-- Draft booking lifecycle (SEAT_SELECTION_MODULE.md §5.1a, review round 5):
-- the draft -> hold -> finalize flow commits a booking BEFORE seats and
-- payment exist, so BookingStatus gains DRAFT. The V1 baseline's CHECK
-- constraint enumerates the old statuses - without this migration every
-- draft insert fails at the database regardless of the Java enum.

ALTER TABLE bookings
    DROP CONSTRAINT bookings_booking_status_check;

ALTER TABLE bookings
    ADD CONSTRAINT bookings_booking_status_check
        CHECK (booking_status IN ('DRAFT', 'CREATED', 'CONFIRMED', 'CANCELLED', 'COMPLETED'));
