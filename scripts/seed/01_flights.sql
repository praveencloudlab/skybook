BEGIN;

-- Capture each route's template (airline, endpoints, time-of-day, duration)
-- from the existing flights before replacing them.
CREATE TEMP TABLE route_tpl AS
  SELECT DISTINCT ON (flight_number)
    flight_number, airline_code, origin_airport_code, destination_airport_code,
    departure_time::time AS dep_time,
    (arrival_time - departure_time) AS duration
  FROM flights
  WHERE arrival_time > departure_time
  ORDER BY flight_number, departure_time;

\echo route templates:
SELECT count(*) FROM route_tpl;

DELETE FROM flights;

-- Daily departures for every route, today (2026-07-14) through +365 days.
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
CROSS JOIN generate_series(DATE '2026-07-14', DATE '2027-07-13', INTERVAL '1 day') AS d;

\echo generated flights:
SELECT count(*) AS flights, min(departure_time)::date AS first_day, max(departure_time)::date AS last_day FROM flights;

COMMIT;
