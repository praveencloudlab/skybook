-- One-time backfill for the persisted fare breakdown
-- (SEAT_SELECTION_MODULE.md §8, build-order step 1). Run against
-- skybook_booking BEFORE deploying the new booking-service image:
--
--   docker exec -i skybook-postgres-1 psql -U postgres -d skybook_booking \
--     < scripts/seed/booking_breakdown_backfill.sql
--
-- Why this is needed: the new columns are NOT NULL, and Hibernate
-- ddl-auto:update cannot add a NOT NULL column to a table that already has
-- rows. Pre-creating the columns here (with backfilled values) means the new
-- app sees them already present and matching, so startup is clean. Safe to
-- run on a fresh DB too (IF NOT EXISTS / guarded updates).

ALTER TABLE booking_passengers ADD COLUMN IF NOT EXISTS base_fare            numeric(38,2);
ALTER TABLE booking_passengers ADD COLUMN IF NOT EXISTS seat_surcharge       numeric(38,2);
ALTER TABLE booking_passengers ADD COLUMN IF NOT EXISTS charged_seat_assignment_mode varchar(10);
ALTER TABLE booking_passengers ADD COLUMN IF NOT EXISTS currency             varchar(3);

-- Backfill existing rows: base fare = the total they were charged, no
-- surcharge, manual (pre-auto-assignment), USD (the existing payment currency).
UPDATE booking_passengers SET base_fare            = fare     WHERE base_fare IS NULL;
UPDATE booking_passengers SET seat_surcharge       = 0        WHERE seat_surcharge IS NULL;
UPDATE booking_passengers SET charged_seat_assignment_mode = 'MANUAL' WHERE charged_seat_assignment_mode IS NULL;
UPDATE booking_passengers SET currency             = 'USD'    WHERE currency IS NULL;

-- Now the NOT NULL constraints the entity declares can be enforced.
ALTER TABLE booking_passengers ALTER COLUMN base_fare            SET NOT NULL;
ALTER TABLE booking_passengers ALTER COLUMN seat_surcharge       SET NOT NULL;
ALTER TABLE booking_passengers ALTER COLUMN charged_seat_assignment_mode SET NOT NULL;
ALTER TABLE booking_passengers ALTER COLUMN currency             SET NOT NULL;
