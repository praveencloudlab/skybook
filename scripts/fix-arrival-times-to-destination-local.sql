-- One-off correction: arrival_time is stored on the ORIGIN's clock and must
-- read as the DESTINATION's.
--
--   docker exec -i skybook-postgres-1 psql -U postgres -d skybook_flight \
--     < scripts/fix-arrival-times-to-destination-local.sql
--
-- WHY
-- Flights were bulk-loaded with both times authored against one clock, so
-- subtracting them gave the real flying time and everything looked right.
-- The arrival itself did not: SQ322 leaves London 21:25 and the row said it
-- landed 10:30, which is 10:30 in London. Singapore's arrivals board reads
-- 17:30. Seven hours out, on the search results, the e-ticket and the
-- confirmation email.
--
-- Shifting the arrival to the destination's clock fixes the displayed time and
-- LEAVES THE DURATION UNCHANGED, because the duration is now measured across
-- the two zones (AirportTimeZones.elapsedBetween) rather than by subtraction.
--
-- Applies only to rows whose two airports are in different zones; a domestic
-- hop is already correct. Flights generated after this release are written
-- correctly at source by FlightScheduleServiceImpl AND by every seed script
-- (01/04/05/06 author destination-local arrivals themselves), so this exists
-- only for databases whose rows predate that - e.g. an environment seeded
-- with the old scripts. Never run it after a seed that authored arrivals
-- correctly: it would shift them a second time.
--
-- SAFETY
-- Running this twice would shift twice and put every arrival badly wrong, so it
-- records itself and becomes a no-op on any later run. Zones are named rather
-- than offsets so each row converts against its own date's DST rules.

CREATE TABLE IF NOT EXISTS flight_data_fixes (
    name        text PRIMARY KEY,
    applied_at  timestamptz NOT NULL DEFAULT now(),
    rows_changed bigint
);

DO $$
DECLARE
    changed bigint;
BEGIN
    IF EXISTS (SELECT 1 FROM flight_data_fixes
               WHERE name = 'arrival_times_to_destination_local') THEN
        RAISE NOTICE 'Already applied - nothing to do.';
        RETURN;
    END IF;

    WITH zones(code, zone) AS (VALUES
        ('ATL','America/New_York'), ('JFK','America/New_York'),
        ('LHR','Europe/London'),    ('MAN','Europe/London'),
        ('BHX','Europe/London'),    ('EDI','Europe/London'),
        ('GLA','Europe/London'),    ('CDG','Europe/Paris'),
        ('FRA','Europe/Berlin'),    ('IST','Europe/Istanbul'),
        ('JNB','Africa/Johannesburg'), ('NBO','Africa/Nairobi'),
        ('DXB','Asia/Dubai'),       ('AUH','Asia/Dubai'),
        ('DOH','Asia/Qatar'),       ('BOM','Asia/Kolkata'),
        ('DEL','Asia/Kolkata'),     ('HKG','Asia/Hong_Kong'),
        ('SIN','Asia/Singapore'),   ('SYD','Australia/Sydney'),
        ('HYD','Asia/Kolkata'),     ('MAA','Asia/Kolkata'),
        ('BLR','Asia/Kolkata'),     ('CCU','Asia/Kolkata'),
        ('LAX','America/Los_Angeles'), ('SFO','America/Los_Angeles'),
        ('ORD','America/Chicago'),  ('DFW','America/Chicago'),
        ('MIA','America/New_York'))
    UPDATE flights f
       SET arrival_time = (f.arrival_time AT TIME ZONE oz.zone) AT TIME ZONE dz.zone
      FROM zones oz, zones dz
     WHERE oz.code = f.origin_airport_code
       AND dz.code = f.destination_airport_code
       AND oz.zone <> dz.zone;

    GET DIAGNOSTICS changed = ROW_COUNT;

    INSERT INTO flight_data_fixes (name, rows_changed)
    VALUES ('arrival_times_to_destination_local', changed);

    RAISE NOTICE 'Corrected % cross-timezone arrival time(s).', changed;
END $$;
