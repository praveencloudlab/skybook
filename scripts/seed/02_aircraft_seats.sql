BEGIN;

-- Clean slate (inventory DB held only trivial leftover test data).
DELETE FROM flight_inventory;
DELETE FROM aircraft_seats;
DELETE FROM aircraft;

-- Two-aircraft fleet: a narrowbody for short-haul European routes and a
-- widebody for long-haul. Seat maps below define the sellable seat numbers.
INSERT INTO aircraft (created_at, updated_at, created_by, version, registration_number, manufacturer, model, total_seats, status) VALUES
  (now(), now(), 'data-seed', 0, 'G-SKYA', 'Airbus',  'A320neo',     180, 'ACTIVE'),
  (now(), now(), 'data-seed', 0, 'G-SKYB', 'Boeing',  '777-300ER',   300, 'ACTIVE');

-- A320neo seat map: rows 1-30 x A-F. Business rows 1-3, economy 4-30.
INSERT INTO aircraft_seats (created_at, updated_at, created_by, version, aircraft_id, seat_number, row_number, seat_type, position, status, exit_row)
SELECT now(), now(), 'data-seed', 0,
  (SELECT id FROM aircraft WHERE registration_number='G-SKYA'),
  rn::text || ltr, rn,
  CASE WHEN rn <= 3 THEN 'BUSINESS' ELSE 'ECONOMY' END,
  CASE ltr WHEN 'A' THEN 'WINDOW' WHEN 'F' THEN 'WINDOW'
           WHEN 'B' THEN 'MIDDLE' WHEN 'E' THEN 'MIDDLE' ELSE 'AISLE' END,
  'ACTIVE', (rn IN (10,11))
FROM generate_series(1,30) AS rn
CROSS JOIN unnest(ARRAY['A','B','C','D','E','F']) AS ltr;

-- 777 seat map: rows 1-50 x A-F. First 1-2, business 3-8, premium 9-14, economy 15-50.
INSERT INTO aircraft_seats (created_at, updated_at, created_by, version, aircraft_id, seat_number, row_number, seat_type, position, status, exit_row)
SELECT now(), now(), 'data-seed', 0,
  (SELECT id FROM aircraft WHERE registration_number='G-SKYB'),
  rn::text || ltr, rn,
  CASE WHEN rn <= 2 THEN 'FIRST'
       WHEN rn <= 8 THEN 'BUSINESS'
       WHEN rn <= 14 THEN 'PREMIUM_ECONOMY'
       ELSE 'ECONOMY' END,
  CASE ltr WHEN 'A' THEN 'WINDOW' WHEN 'F' THEN 'WINDOW'
           WHEN 'B' THEN 'MIDDLE' WHEN 'E' THEN 'MIDDLE' ELSE 'AISLE' END,
  'ACTIVE', (rn IN (20,21))
FROM generate_series(1,50) AS rn
CROSS JOIN unnest(ARRAY['A','B','C','D','E','F']) AS ltr;

\echo aircraft + seat counts:
SELECT a.registration_number, a.total_seats, count(s.id) AS seat_rows
FROM aircraft a JOIN aircraft_seats s ON s.aircraft_id=a.id
GROUP BY 1,2 ORDER BY 1;

-- flight_inventory per flight comes next (needs flight ids piped from the
-- other database into tmp_flights first).
COMMIT;
