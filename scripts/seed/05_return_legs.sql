BEGIN;

-- Return legs (round-trip feature): every base route is UK-outbound only, so
-- a round trip had no inbound flights to offer. Mirror each base-route flight:
-- swapped endpoints, departing 2h after the outbound arrives, same duration,
-- flight number suffixed '9' (EK001 -> EK0019). ADDITIVE and idempotent.
--
-- Both stored times are wall clocks at their OWN airports (arrivals
-- destination-local), so the block time is the difference of the two INSTANTS,
-- never of the raw columns - and the mirrored arrival is converted back onto
-- the origin airport's clock. Zone names mirror AirportTimeZones.java.
DELETE FROM flights WHERE created_by = 'data-seed-returns';

CREATE TEMP TABLE return_zones (code varchar(3) PRIMARY KEY, zone text NOT NULL);
INSERT INTO return_zones VALUES
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

INSERT INTO flights
  (created_at, updated_at, created_by, updated_by, version,
   airline_code, arrival_time, departure_time,
   destination_airport_code, flight_number, origin_airport_code, status, schedule_id)
SELECT now(), now(), 'data-seed-returns', NULL, 0,
  f.airline_code,
  (((f.arrival_time + INTERVAL '2 hours') AT TIME ZONE COALESCE(dz.zone,'UTC'))
     + ((f.arrival_time AT TIME ZONE COALESCE(dz.zone,'UTC'))
      - (f.departure_time AT TIME ZONE COALESCE(oz.zone,'UTC'))))
    AT TIME ZONE COALESCE(oz.zone,'UTC'),
  f.arrival_time + INTERVAL '2 hours',
  f.origin_airport_code,
  f.flight_number || '9',
  f.destination_airport_code,
  'SCHEDULED', NULL
FROM flights f
LEFT JOIN return_zones oz ON oz.code = f.origin_airport_code
LEFT JOIN return_zones dz ON dz.code = f.destination_airport_code
WHERE f.created_by = 'data-seed';

SELECT count(*) AS return_legs FROM flights WHERE created_by = 'data-seed-returns';
COMMIT;
