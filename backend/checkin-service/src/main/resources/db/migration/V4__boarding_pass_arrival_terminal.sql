-- The arrival terminal joins the boarding pass (user request): shown under
-- the destination airport so a transferring or arriving passenger knows
-- where they land. Backfill reuses sb_terminal() from V3.
ALTER TABLE boarding_passes ADD COLUMN arrival_terminal varchar(4);

UPDATE boarding_passes
SET arrival_terminal = sb_terminal(substring(flight_number from 1 for 2), destination_airport_code)
WHERE arrival_terminal IS NULL AND flight_number IS NOT NULL AND destination_airport_code IS NOT NULL;
