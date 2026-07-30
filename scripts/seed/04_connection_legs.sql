BEGIN;

-- Hub-onward legs (connections feature): the original 30 routes all START in
-- the UK, so no itinerary could ever connect - hubs had no outbound flights.
-- These templates give DXB/DOH/AUH/IST/CDG/SIN onward departures timed to
-- make sane layovers (roughly 1-7h) against the existing arrivals, enabling
-- 1-stop (e.g. LHR-DXB-BOM) and 2-stop (e.g. EDI-CDG-IST-DEL) itineraries.
--
-- ADDITIVE on purpose: unlike 01_flights.sql this never touches existing
-- rows - bookings hold flight ids, and a re-seed that replaced them would
-- orphan every booking made so far. Idempotent per flight number.
CREATE TEMP TABLE onward_tpl (
  flight_number             varchar(10),
  airline_code              varchar(10),
  origin_airport_code       varchar(10),
  destination_airport_code  varchar(10),
  dep_time                  time,
  duration                  interval
);

INSERT INTO onward_tpl VALUES
  ('EK512', 'EK', 'DXB', 'BOM', TIME '18:10', INTERVAL '190 minutes'),
  ('EK506', 'EK', 'DXB', 'DEL', TIME '17:45', INTERVAL '205 minutes'),
  ('EK354', 'EK', 'DXB', 'SIN', TIME '18:40', INTERVAL '445 minutes'),
  ('EK434', 'EK', 'DXB', 'SYD', TIME '21:30', INTERVAL '830 minutes'),
  ('EK382', 'EK', 'DXB', 'HKG', TIME '19:05', INTERVAL '465 minutes'),
  ('QR502', 'QR', 'DOH', 'BOM', TIME '20:30', INTERVAL '195 minutes'),
  ('QR562', 'QR', 'DOH', 'DEL', TIME '21:00', INTERVAL '210 minutes'),
  ('EY424', 'EY', 'AUH', 'SYD', TIME '09:45', INTERVAL '815 minutes'),
  ('TK1822', 'TK', 'CDG', 'IST', TIME '11:45', INTERVAL '210 minutes'),
  ('TK720', 'TK', 'IST', 'DEL', TIME '18:35', INTERVAL '390 minutes'),
  ('SQ231', 'SQ', 'SIN', 'SYD', TIME '09:40', INTERVAL '460 minutes');

-- Idempotency: re-running replaces only THESE onward flights (no bookings
-- reference them on first run; on re-runs ids change only for these legs).
DELETE FROM flights WHERE flight_number IN (SELECT flight_number FROM onward_tpl);

INSERT INTO flights
  (created_at, updated_at, created_by, updated_by, version,
   airline_code, arrival_time, departure_time,
   destination_airport_code, flight_number, origin_airport_code, status, schedule_id)
SELECT now(), now(), 'data-seed-connections', NULL, 0,
  r.airline_code,
  (d::date + r.dep_time) + r.duration,
  (d::date + r.dep_time),
  r.destination_airport_code, r.flight_number, r.origin_airport_code, 'SCHEDULED', NULL
FROM onward_tpl r
CROSS JOIN generate_series(CURRENT_DATE, CURRENT_DATE + 365, INTERVAL '1 day') AS d;

\echo onward flights generated:
SELECT count(*) FROM flights WHERE created_by = 'data-seed-connections';

DO $$
DECLARE n bigint;
BEGIN
  SELECT count(*) INTO n FROM flights WHERE created_by = 'data-seed-connections';
  IF n = 0 THEN
    RAISE EXCEPTION 'Connection seed produced 0 flights.';
  END IF;
END $$;

COMMIT;
