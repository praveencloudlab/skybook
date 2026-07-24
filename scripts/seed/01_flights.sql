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
  ('VS300', 'VS', 'LHR', 'JFK', TIME '11:10', INTERVAL '490 minutes');

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
  (d::date + r.dep_time) + r.duration,
  (d::date + r.dep_time),
  r.destination_airport_code, r.flight_number, r.origin_airport_code, 'SCHEDULED', NULL
FROM route_tpl r
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
