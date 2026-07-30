BEGIN;

-- Return legs (round-trip feature): every base route is UK-outbound only, so
-- a round trip had no inbound flights to offer. Mirror each base-route flight:
-- swapped endpoints, departing 2h after the outbound arrives, same duration,
-- flight number suffixed '9' (EK001 -> EK0019). ADDITIVE and idempotent.
DELETE FROM flights WHERE created_by = 'data-seed-returns';

INSERT INTO flights
  (created_at, updated_at, created_by, updated_by, version,
   airline_code, arrival_time, departure_time,
   destination_airport_code, flight_number, origin_airport_code, status, schedule_id)
SELECT now(), now(), 'data-seed-returns', NULL, 0,
  f.airline_code,
  (f.arrival_time + INTERVAL '2 hours') + (f.arrival_time - f.departure_time),
  f.arrival_time + INTERVAL '2 hours',
  f.origin_airport_code,
  f.flight_number || '9',
  f.destination_airport_code,
  'SCHEDULED', NULL
FROM flights f
WHERE f.created_by = 'data-seed';

SELECT count(*) AS return_legs FROM flights WHERE created_by = 'data-seed-returns';
COMMIT;
