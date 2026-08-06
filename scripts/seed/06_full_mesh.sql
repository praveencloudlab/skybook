BEGIN;

-- Full-mesh schedule (Skyscanner-style coverage): EVERY directed pair of the
-- 29 known airports gets 3 daily 'SB' (SkyBook Air) departures for a year
-- from CURRENT_DATE - so a fresh install is dense from its install day, and
-- every origin/destination a visitor can pick returns real bookable flights.
--
-- ADDITIVE and idempotent: never deletes, skips (flight_number, day) rows
-- that already exist. Real-airline routes from 01_flights.sql stay - on
-- those routes SB simply adds frequency, like a second carrier would.
-- Run against skybook_flight.

-- zone mirrors AirportTimeZones.java: arrivals are AUTHORED destination-local
-- (the platform contract), not corrected afterwards - this script is additive,
-- so a blanket after-the-fact shift would also move rows a previous run
-- already wrote correctly.
CREATE TEMP TABLE mesh_airports (code varchar(3) PRIMARY KEY, region varchar(6) NOT NULL, zone text NOT NULL);
INSERT INTO mesh_airports VALUES
  ('LHR','UK','Europe/London'), ('MAN','UK','Europe/London'), ('EDI','UK','Europe/London'),
  ('GLA','UK','Europe/London'), ('BHX','UK','Europe/London'),
  ('CDG','EU','Europe/Paris'), ('FRA','EU','Europe/Berlin'),
  ('IST','TR','Europe/Istanbul'),
  ('DXB','GULF','Asia/Dubai'), ('AUH','GULF','Asia/Dubai'), ('DOH','GULF','Asia/Qatar'),
  ('DEL','SASIA','Asia/Kolkata'), ('BOM','SASIA','Asia/Kolkata'),
  ('HKG','EASIA','Asia/Hong_Kong'), ('SIN','EASIA','Asia/Singapore'),
  ('SYD','OCE','Australia/Sydney'),
  ('JFK','NAME','America/New_York'), ('ATL','NAME','America/New_York'), ('MIA','NAME','America/New_York'),
  ('ORD','NAMC','America/Chicago'), ('DFW','NAMC','America/Chicago'),
  ('LAX','NAMW','America/Los_Angeles'), ('SFO','NAMW','America/Los_Angeles'),
  ('HYD','SASIA','Asia/Kolkata'), ('MAA','SASIA','Asia/Kolkata'), ('BLR','SASIA','Asia/Kolkata'),
  ('CCU','SASIA','Asia/Kolkata'),
  ('JNB','AFR','Africa/Johannesburg'), ('NBO','AFR','Africa/Nairobi');

-- Approximate block times (minutes) between regions; symmetric.
CREATE TEMP TABLE region_dur (r1 varchar(6), r2 varchar(6), mins int);
INSERT INTO region_dur VALUES
  ('UK','NAME',490),   ('UK','NAMC',560),   ('UK','NAMW',660),
  ('EU','NAME',510),   ('EU','NAMC',580),   ('EU','NAMW',690),
  ('TR','NAME',630),   ('TR','NAMC',700),   ('TR','NAMW',790),
  ('GULF','NAME',800), ('GULF','NAMC',860), ('GULF','NAMW',960),
  ('SASIA','NAME',870),('SASIA','NAMC',890),('SASIA','NAMW',940),
  ('EASIA','NAME',950),('EASIA','NAMC',880),('EASIA','NAMW',780),
  ('OCE','NAME',1230), ('OCE','NAMC',1120), ('OCE','NAMW',830),
  ('AFR','NAME',900),  ('AFR','NAMC',960),  ('AFR','NAMW',1080),
  ('NAME','NAME',130), ('NAMC','NAMC',135), ('NAMW','NAMW',85),
  ('NAME','NAMC',160), ('NAME','NAMW',330), ('NAMC','NAMW',225),
  ('UK','UK',75),      ('UK','EU',100),    ('UK','TR',250),   ('UK','GULF',420),
  ('UK','SASIA',540),  ('UK','EASIA',740), ('UK','OCE',1290),   ('UK','AFR',600),
  ('EU','EU',90),      ('EU','TR',180),    ('EU','GULF',360), ('EU','SASIA',500),
  ('EU','EASIA',720),  ('EU','OCE',1260),    ('EU','AFR',620),
  ('TR','TR',60),      ('TR','GULF',240),  ('TR','SASIA',380),('TR','EASIA',600),
  ('TR','OCE',1150),      ('TR','AFR',500),
  ('GULF','GULF',70),  ('GULF','SASIA',200),('GULF','EASIA',440),
  ('GULF','OCE',840),   ('GULF','AFR',480),
  ('SASIA','SASIA',90),('SASIA','EASIA',330),('SASIA','OCE',750),
   ('SASIA','AFR',540),
  ('EASIA','EASIA',220),('EASIA','OCE',480),('EASIA','AFR',640),
  ('OCE','OCE',90),     ('OCE','AFR',840),
     
  ('AFR','AFR',240);

-- Directed pairs with a stable id (drives the SB flight number) and duration.
CREATE TEMP TABLE mesh_pairs AS
SELECT row_number() OVER (ORDER BY o.code, d.code) AS pair_id,
       o.code AS origin, d.code AS destination,
       o.zone AS origin_zone, d.zone AS dest_zone,
       rd.mins
FROM mesh_airports o
JOIN mesh_airports d ON d.code <> o.code
JOIN region_dur rd
  ON (rd.r1 = o.region AND rd.r2 = d.region) OR (rd.r1 = d.region AND rd.r2 = o.region);

\echo mesh pairs (expect 380):
SELECT count(*) FROM mesh_pairs;

-- Three daily waves; per-pair minute jitter so departures don't all align.
CREATE TEMP TABLE mesh_slots (slot int, base time);
INSERT INTO mesh_slots VALUES (0, TIME '06:40'), (1, TIME '12:55'), (2, TIME '19:35');

INSERT INTO flights
  (created_at, updated_at, created_by, updated_by, version,
   airline_code, arrival_time, departure_time,
   destination_airport_code, flight_number, origin_airport_code, status, schedule_id)
SELECT now(), now(), 'data-seed-mesh', NULL, 0,
  'SB',
  -- p.mins is the block time; the stored arrival must read on the
  -- DESTINATION's clock (each end resolved on its own zone, DST-correct).
  ((dep.ts AT TIME ZONE p.origin_zone) + make_interval(mins => p.mins)) AT TIME ZONE p.dest_zone,
  dep.ts,
  p.destination,
  'SB' || lpad((1000 + p.pair_id * 3 + s.slot)::text, 4, '0'),
  p.origin,
  'SCHEDULED', NULL
FROM mesh_pairs p
CROSS JOIN mesh_slots s
CROSS JOIN generate_series(CURRENT_DATE, CURRENT_DATE + 365, INTERVAL '1 day') AS d
CROSS JOIN LATERAL (
  SELECT (d::date + s.base)
         + make_interval(mins => abs(hashtext(p.origin || p.destination || s.slot::text)) % 45) AS ts
) dep
WHERE NOT EXISTS (
  SELECT 1 FROM flights f
  WHERE f.flight_number = 'SB' || lpad((1000 + p.pair_id * 3 + s.slot)::text, 4, '0')
    AND f.departure_time::date = d::date
);

\echo mesh flights now in table:
SELECT count(*) AS sb_flights, min(departure_time)::date AS first_day, max(departure_time)::date AS last_day
FROM flights WHERE created_by = 'data-seed-mesh';

DO $$
DECLARE n bigint;
BEGIN
  SELECT count(*) INTO n FROM flights WHERE created_by = 'data-seed-mesh';
  IF n = 0 THEN
    RAISE EXCEPTION 'Mesh seed produced 0 flights - pair/duration join failed.';
  END IF;
END $$;

-- Route+departure index: at this volume every search/calendar/itinerary scan
-- needs it. Mirrors the @Index on the Flight entity (fresh installs get it
-- from ddl-auto; this covers databases created before the entity carried it).
CREATE INDEX IF NOT EXISTS idx_flights_route_departure
  ON flights (origin_airport_code, destination_airport_code, departure_time);

COMMIT;
