-- Seat-selection fare breakdown (SEAT_SELECTION_MODULE.md §8): persist the
-- ORIGINAL charge composition per passenger so refunds, invoices, and
-- check-in entitlement comparisons never recompute historical charges from
-- current pricing config.
--
-- Explicit ALTER -> UPDATE -> SET NOT NULL because Postgres rejects adding a
-- NOT NULL column to a populated table and ddl-auto never runs data
-- transforms (design review, rounds 2-4).

ALTER TABLE booking_passengers
    ADD COLUMN base_fare numeric(38,2),
    ADD COLUMN seat_surcharge numeric(38,2),
    ADD COLUMN charged_seat_assignment_mode varchar(10),
    ADD COLUMN currency varchar(3);

-- Backfill rows created before this branch: their whole fare was the cabin
-- base fare, no surcharge existed, every seat was manually supplied
-- (ManualSeatAssignmentStrategy was the only implementation), USD-only v1.
UPDATE booking_passengers
SET base_fare                    = fare,
    seat_surcharge               = 0.00,
    charged_seat_assignment_mode = 'MANUAL',
    currency                     = 'USD'
WHERE base_fare IS NULL;

ALTER TABLE booking_passengers
    ALTER COLUMN base_fare SET NOT NULL,
    ALTER COLUMN seat_surcharge SET NOT NULL,
    ALTER COLUMN charged_seat_assignment_mode SET NOT NULL,
    ALTER COLUMN currency SET NOT NULL;

ALTER TABLE booking_passengers
    ADD CONSTRAINT booking_passengers_charged_mode_check
        CHECK (charged_seat_assignment_mode IN ('AUTO', 'MANUAL'));

-- Draft-stage bookings hold passengers before any seat is assigned
-- (BookingFacade draft -> hold -> finalize flow, design §5.1). Hibernate
-- validate does not check nullability, so entity/DB stay aligned.
ALTER TABLE booking_passengers
    ALTER COLUMN seat_number DROP NOT NULL;
