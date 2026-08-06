BEGIN;

-- Route templates: airline, endpoints, local departure time and duration.
--
-- These are LITERAL, generated from scripts/seed/routes.json (the canonical
-- 30-route definition). They used to be derived with
-- "SELECT DISTINCT ON (flight_number) ... FROM flights" - i.e. from the very
-- table this script then DELETEs and repopulates. That silently assumed flights
-- already existed, so on any FRESH database it produced 0 templates and
-- therefore 0 flights, while still exiting 0. It only ever worked on a machine
-- whose database had been populated by earlier manual use; a clean clone, a new
-- contributor, or CI got an empty schedule and a "successful" seed.
CREATE TEMP TABLE route_tpl (
  flight_number             varchar(10),
  airline_code              varchar(10),
  origin_airport_code       varchar(10),
  destination_airport_code  varchar(10),
  dep_time                  time,
  duration                  interval
);

INSERT INTO route_tpl VALUES
  ('AF1380', 'AF', 'LHR', 'CDG', TIME '07:30', INTERVAL '80 minutes'),
  ('AF1680', 'AF', 'EDI', 'CDG', TIME '08:15', INTERVAL '140 minutes'),
  ('AI117', 'AI', 'LHR', 'BOM', TIME '14:35', INTERVAL '575 minutes'),
  ('AI121', 'AI', 'BHX', 'DEL', TIME '16:35', INTERVAL '530 minutes'),
  ('AI131', 'AI', 'LHR', 'DEL', TIME '14:25', INTERVAL '515 minutes'),
  ('AI173', 'AI', 'GLA', 'DEL', TIME '15:55', INTERVAL '540 minutes'),
  ('BA035', 'BA', 'LHR', 'HKG', TIME '11:25', INTERVAL '710 minutes'),
  ('BA075', 'BA', 'LHR', 'JNB', TIME '19:30', INTERVAL '665 minutes'),
  ('BA117', 'BA', 'LHR', 'BOM', TIME '21:40', INTERVAL '585 minutes'),
  ('BA178', 'BA', 'LHR', 'JFK', TIME '10:15', INTERVAL '490 minutes'),
  ('BA257', 'BA', 'LHR', 'NBO', TIME '20:35', INTERVAL '495 minutes'),
  ('CX238', 'CX', 'LHR', 'HKG', TIME '21:10', INTERVAL '715 minutes'),
  ('EK001', 'EK', 'LHR', 'DXB', TIME '08:25', INTERVAL '415 minutes'),
  ('EK007', 'EK', 'MAN', 'DXB', TIME '20:55', INTERVAL '410 minutes'),
  ('EK009', 'EK', 'GLA', 'DXB', TIME '21:15', INTERVAL '410 minutes'),
  ('EK030', 'EK', 'BHX', 'DXB', TIME '09:35', INTERVAL '405 minutes'),
  ('EY012', 'EY', 'MAN', 'AUH', TIME '20:35', INTERVAL '450 minutes'),
  ('EY017', 'EY', 'LHR', 'AUH', TIME '21:50', INTERVAL '435 minutes'),
  ('LH900', 'LH', 'LHR', 'FRA', TIME '07:10', INTERVAL '105 minutes'),
  ('LH908', 'LH', 'MAN', 'FRA', TIME '06:50', INTERVAL '115 minutes'),
  ('QF002', 'QF', 'LHR', 'SYD', TIME '21:20', INTERVAL '1315 minutes'),
  ('QR003', 'QR', 'EDI', 'DOH', TIME '20:05', INTERVAL '395 minutes'),
  ('QR013', 'QR', 'LHR', 'DOH', TIME '20:35', INTERVAL '380 minutes'),
  ('QR017', 'QR', 'LHR', 'DOH', TIME '13:05', INTERVAL '380 minutes'),
  ('SQ322', 'SQ', 'LHR', 'SIN', TIME '21:25', INTERVAL '785 minutes'),
  ('SQ326', 'SQ', 'MAN', 'SIN', TIME '13:10', INTERVAL '805 minutes'),
  ('TK1980', 'TK', 'LHR', 'IST', TIME '07:55', INTERVAL '265 minutes'),
  ('TK1984', 'TK', 'EDI', 'IST', TIME '09:20', INTERVAL '285 minutes'),
  ('VS103', 'VS', 'MAN', 'ATL', TIME '11:55', INTERVAL '545 minutes'),
  ('VS300', 'VS', 'LHR', 'JFK', TIME '11:10', INTERVAL '490 minutes'),
  ('BA277', 'BA', 'LHR', 'HYD', TIME '13:15', INTERVAL '555 minutes'),
  ('BA276', 'BA', 'HYD', 'LHR', TIME '06:30', INTERVAL '590 minutes'),
  ('BA119', 'BA', 'LHR', 'BLR', TIME '11:50', INTERVAL '580 minutes'),
  ('BA118', 'BA', 'BLR', 'LHR', TIME '06:55', INTERVAL '615 minutes'),
  ('BA255', 'BA', 'LHR', 'MAA', TIME '13:40', INTERVAL '585 minutes'),
  ('BA254', 'BA', 'MAA', 'LHR', TIME '07:10', INTERVAL '620 minutes'),
  ('AI136', 'AI', 'LHR', 'CCU', TIME '09:30', INTERVAL '555 minutes'),
  ('AI135', 'AI', 'CCU', 'LHR', TIME '13:30', INTERVAL '610 minutes'),
  ('SB5101', 'SB', 'MAN', 'DEL', TIME '21:05', INTERVAL '505 minutes'),
  ('SB5102', 'SB', 'DEL', 'MAN', TIME '09:20', INTERVAL '560 minutes'),
  ('SB5103', 'SB', 'MAN', 'BOM', TIME '10:40', INTERVAL '530 minutes'),
  ('SB5104', 'SB', 'BOM', 'MAN', TIME '16:45', INTERVAL '585 minutes'),
  ('SB5105', 'SB', 'MAN', 'JFK', TIME '11:10', INTERVAL '470 minutes'),
  ('SB5106', 'SB', 'JFK', 'MAN', TIME '19:30', INTERVAL '415 minutes'),
  ('SB5107', 'SB', 'MAN', 'ORD', TIME '09:45', INTERVAL '520 minutes'),
  ('SB5108', 'SB', 'ORD', 'MAN', TIME '17:55', INTERVAL '455 minutes'),
  ('EK528', 'EK', 'DXB', 'HYD', TIME '03:15', INTERVAL '215 minutes'),
  ('EK527', 'EK', 'HYD', 'DXB', TIME '21:55', INTERVAL '235 minutes'),
  ('EK568', 'EK', 'DXB', 'BLR', TIME '04:05', INTERVAL '225 minutes'),
  ('EK567', 'EK', 'BLR', 'DXB', TIME '22:20', INTERVAL '245 minutes'),
  ('EK544', 'EK', 'DXB', 'MAA', TIME '03:40', INTERVAL '240 minutes'),
  ('EK543', 'EK', 'MAA', 'DXB', TIME '21:40', INTERVAL '255 minutes'),
  ('BA283', 'BA', 'LHR', 'LAX', TIME '14:15', INTERVAL '660 minutes'),
  ('BA282', 'BA', 'LAX', 'LHR', TIME '17:20', INTERVAL '620 minutes'),
  ('BA287', 'BA', 'LHR', 'SFO', TIME '11:25', INTERVAL '655 minutes'),
  ('BA286', 'BA', 'SFO', 'LHR', TIME '16:35', INTERVAL '615 minutes'),
  ('BA297', 'BA', 'LHR', 'ORD', TIME '14:00', INTERVAL '530 minutes'),
  ('BA296', 'BA', 'ORD', 'LHR', TIME '18:10', INTERVAL '465 minutes'),
  ('BA207', 'BA', 'LHR', 'MIA', TIME '14:35', INTERVAL '560 minutes'),
  ('BA206', 'BA', 'MIA', 'LHR', TIME '18:20', INTERVAL '505 minutes'),
  ('BA193', 'BA', 'LHR', 'DFW', TIME '12:20', INTERVAL '585 minutes'),
  ('BA192', 'BA', 'DFW', 'LHR', TIME '16:40', INTERVAL '540 minutes'),
  ('AI102', 'AI', 'JFK', 'DEL', TIME '12:30', INTERVAL '810 minutes'),
  ('AI101', 'AI', 'DEL', 'JFK', TIME '01:45', INTERVAL '900 minutes'),
  ('AI175', 'AI', 'SFO', 'DEL', TIME '20:30', INTERVAL '900 minutes'),
  ('AI176', 'AI', 'DEL', 'SFO', TIME '04:05', INTERVAL '955 minutes'),
  ('AI126', 'AI', 'ORD', 'DEL', TIME '13:35', INTERVAL '830 minutes'),
  ('AI125', 'AI', 'DEL', 'ORD', TIME '02:15', INTERVAL '895 minutes'),
  ('SB5201', 'SB', 'DEL', 'HYD', TIME '08:10', INTERVAL '130 minutes'),
  ('SB5202', 'SB', 'HYD', 'DEL', TIME '11:20', INTERVAL '135 minutes'),
  ('SB5203', 'SB', 'BOM', 'BLR', TIME '09:05', INTERVAL '100 minutes'),
  ('SB5204', 'SB', 'BLR', 'BOM', TIME '12:00', INTERVAL '105 minutes'),
  ('SB5205', 'SB', 'DEL', 'MAA', TIME '07:35', INTERVAL '165 minutes'),
  ('SB5206', 'SB', 'MAA', 'DEL', TIME '11:15', INTERVAL '170 minutes'),
  ('SB5207', 'SB', 'DEL', 'CCU', TIME '06:40', INTERVAL '140 minutes'),
  ('SB5208', 'SB', 'CCU', 'DEL', TIME '09:50', INTERVAL '145 minutes');

-- Mirror of AirportTimeZones.java. duration above is the BLOCK time; the
-- stored arrival must read on the DESTINATION's clock (platform contract),
-- so each row is converted through real zone names - DST-correct per date.
-- Authored at source: no after-the-fact fix pass to chain, nothing to
-- double-shift on a re-run.
CREATE TEMP TABLE seed_zones (code varchar(3) PRIMARY KEY, zone text NOT NULL);
INSERT INTO seed_zones VALUES
  ('ATL','America/New_York'), ('JFK','America/New_York'), ('MIA','America/New_York'),
  ('ORD','America/Chicago'),  ('DFW','America/Chicago'),
  ('LAX','America/Los_Angeles'), ('SFO','America/Los_Angeles'),
  ('LHR','Europe/London'), ('MAN','Europe/London'), ('BHX','Europe/London'),
  ('EDI','Europe/London'), ('GLA','Europe/London'),
  ('CDG','Europe/Paris'), ('FRA','Europe/Berlin'), ('IST','Europe/Istanbul'),
  ('JNB','Africa/Johannesburg'), ('NBO','Africa/Nairobi'),
  ('DXB','Asia/Dubai'), ('AUH','Asia/Dubai'), ('DOH','Asia/Qatar'),
  ('BOM','Asia/Kolkata'), ('DEL','Asia/Kolkata'), ('HYD','Asia/Kolkata'),
  ('MAA','Asia/Kolkata'), ('BLR','Asia/Kolkata'), ('CCU','Asia/Kolkata'),
  ('HKG','Asia/Hong_Kong'), ('SIN','Asia/Singapore'), ('SYD','Australia/Sydney');

\echo route templates:
SELECT count(*) FROM route_tpl;

DELETE FROM flights;

-- Daily departures for every route, from TODAY through +365 days.
-- Relative to CURRENT_DATE, not a hard-coded window: the previous fixed
-- 2026-07-14..2027-07-13 range would quietly stop covering "tomorrow" once that
-- window aged out, which is exactly the seed-drift risk the e2e design flagged.
INSERT INTO flights
  (created_at, updated_at, created_by, updated_by, version,
   airline_code, arrival_time, departure_time,
   destination_airport_code, flight_number, origin_airport_code, status, schedule_id)
SELECT now(), now(), 'data-seed', NULL, 0,
  r.airline_code,
  (((d::date + r.dep_time) AT TIME ZONE COALESCE(oz.zone,'UTC')) + r.duration)
    AT TIME ZONE COALESCE(dz.zone,'UTC'),
  (d::date + r.dep_time),
  r.destination_airport_code, r.flight_number, r.origin_airport_code, 'SCHEDULED', NULL
FROM route_tpl r
LEFT JOIN seed_zones oz ON oz.code = r.origin_airport_code
LEFT JOIN seed_zones dz ON dz.code = r.destination_airport_code
CROSS JOIN generate_series(CURRENT_DATE, CURRENT_DATE + 365, INTERVAL '1 day') AS d;

\echo generated flights:
SELECT count(*) AS flights, min(departure_time)::date AS first_day, max(departure_time)::date AS last_day FROM flights;

-- Fail loudly rather than leaving a "successful" empty seed behind.
DO $$
DECLARE n bigint;
BEGIN
  SELECT count(*) INTO n FROM flights;
  IF n = 0 THEN
    RAISE EXCEPTION 'Seed produced 0 flights - route templates missing or insert failed.';
  END IF;
END $$;

COMMIT;
