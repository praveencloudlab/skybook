-- Re-fleet the schedule onto the modern aircraft (user request): long-haul
-- flights move to the A350-1000 / 787-9, short-haul alternates A320neo /
-- A321XLR. ONLY flights with untouched inventory are re-fleeted - a flight
-- holding seats or reservations keeps its metal, because sold cabins must
-- never be swapped under passengers' seats.
--
-- Expects tmp_refleet(flight_id, origin, dest) staged by the runner (COPY
-- from skybook_flight, same pattern as seed.sh stage 3). Idempotent: keyed
-- purely on route classification, so re-running converges.
--
-- Fleet (registrations from 02_aircraft_seats.sql + the add-fleet API run):
--   G-SKYA A320neo (short)   G-SKYB 777-300ER (Gulf trunk)
--   G-SKYC A350-1000 (far east/south)   G-SKYD 787-9 (americas/india/africa)
--   G-SKYE A321XLR (short, long thin)

WITH ids AS (
  SELECT
    (SELECT id FROM aircraft WHERE registration_number = 'G-SKYA') AS a320,
    (SELECT id FROM aircraft WHERE registration_number = 'G-SKYB') AS b777,
    (SELECT id FROM aircraft WHERE registration_number = 'G-SKYC') AS a350,
    (SELECT id FROM aircraft WHERE registration_number = 'G-SKYD') AS b789,
    (SELECT id FROM aircraft WHERE registration_number = 'G-SKYE') AS a321
),
classified AS (
  SELECT t.flight_id,
    CASE
      -- the non-UK endpoint decides the mission
      WHEN far.code IN ('SYD', 'SIN', 'HKG', 'JNB', 'LAX', 'SFO') THEN (SELECT a350 FROM ids)
      WHEN far.code IN ('JFK', 'ATL', 'DEL', 'BOM', 'NBO', 'ORD', 'DFW', 'MIA', 'HYD', 'MAA', 'BLR', 'CCU') THEN (SELECT b789 FROM ids)
      WHEN far.code = 'DXB' THEN (SELECT b777 FROM ids)
      WHEN far.code IN ('DOH', 'AUH') THEN (SELECT b789 FROM ids)
      -- short-haul (Europe + UK domestic): alternate the narrow-bodies
      WHEN t.flight_id % 2 = 0 THEN (SELECT a321 FROM ids)
      ELSE (SELECT a320 FROM ids)
    END AS new_aircraft_id
  FROM tmp_refleet t
  CROSS JOIN LATERAL (
    SELECT CASE
      WHEN t.dest IN ('LHR', 'MAN', 'EDI', 'GLA', 'BHX') THEN t.origin
      ELSE t.dest
    END AS code
  ) far
)
UPDATE flight_inventory fi
SET aircraft_id = c.new_aircraft_id,
    total_seats = a.total_seats,
    available_seats = a.total_seats - fi.blocked_seats
FROM classified c
JOIN aircraft a ON a.id = c.new_aircraft_id
WHERE fi.flight_id = c.flight_id
  AND fi.aircraft_id IS DISTINCT FROM c.new_aircraft_id
  -- untouched inventory only: nothing held, nothing reserved, ever
  AND fi.held_seats = 0
  AND fi.reserved_seats = 0
  AND NOT EXISTS (SELECT 1 FROM seat_holds h WHERE h.flight_inventory_id = fi.id)
  AND NOT EXISTS (SELECT 1 FROM seat_reservations sr WHERE sr.flight_inventory_id = fi.id);
