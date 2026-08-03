BEGIN;

-- Full-mesh schedule (Skyscanner-style coverage): EVERY directed pair of the
-- 20 known airports gets 3 daily 'SB' (SkyBook Air) departures for a year
-- from CURRENT_DATE - so a fresh install is dense from its install day, and
-- every origin/destination a visitor can pick returns real bookable flights.
--
-- ADDITIVE and idempotent: never deletes, skips (flight_number, day) rows
-- that already exist. Real-airline routes from 01_flights.sql stay - on
-- those routes SB simply adds frequency, like a second carrier would.
-- Run against skybook_flight.

CREATE TEMP TABLE mesh_airports (code varchar(3) PRIMARY KEY, region varchar(6) NOT NULL);
INSERT INTO mesh_airports VALUES
  ('LHR','UK'), ('MAN','UK'), ('EDI','UK'), ('GLA','UK'), ('BHX','UK'),
  ('CDG','EU'), ('FRA','EU'),
  ('IST','TR'),
  ('DXB','GULF'), ('AUH','GULF'), ('DOH','GULF'),
  ('DEL','SASIA'), ('BOM','SASIA'),
  ('HKG','EASIA'), ('SIN','EASIA'),
  ('SYD','OCE'),
  ('JFK','NAM'), ('ATL','NAM'),
  ('JNB','AFR'), ('NBO','AFR');

-- Approximate block times (minutes) between regions; symmetric.
CREATE TEMP TABLE region_dur (r1 varchar(6), r2 varchar(6), mins int);
INSERT INTO region_dur VALUES
  ('UK','UK',75),      ('UK','EU',100),    ('UK','TR',250),   ('UK','GULF',420),
  ('UK','SASIA',540),  ('UK','EASIA',740), ('UK','OCE',1290), ('UK','NAM',490),  ('UK','AFR',600),
  ('EU','EU',90),      ('EU','TR',180),    ('EU','GULF',360), ('EU','SASIA',500),
  ('EU','EASIA',720),  ('EU','OCE',1260),  ('EU','NAM',500),  ('EU','AFR',620),
  ('TR','TR',60),      ('TR','GULF',240),  ('TR','SASIA',380),('TR','EASIA',600),
  ('TR','OCE',1150),   ('TR','NAM',630),   ('TR','AFR',500),
  ('GULF','GULF',70),  ('GULF','SASIA',200),('GULF','EASIA',440),
  ('GULF','OCE',840),  ('GULF','NAM',780), ('GULF','AFR',480),
  ('SASIA','SASIA',90),('SASIA','EASIA',330),('SASIA','OCE',750),
  ('SASIA','NAM',900), ('SASIA','AFR',540),
  ('EASIA','EASIA',220),('EASIA','OCE',480),('EASIA','NAM',950),('EASIA','AFR',640),
  ('OCE','OCE',90),    ('OCE','NAM',1200), ('OCE','AFR',840),
  ('NAM','NAM',140),   ('NAM','AFR',900),
  ('AFR','AFR',240);

-- Directed pairs with a stable id (drives the SB flight number) and duration.
CREATE TEMP TABLE mesh_pairs AS
SELECT row_number() OVER (ORDER BY o.code, d.code) AS pair_id,
       o.code AS origin, d.code AS destination,
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
  dep.ts + make_interval(mins => p.mins),
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
