-- Inventory for every flight that doesn't have one yet (the mesh fills, plus
-- any other stragglers). Assumes tmp_all_flights(flight_id, short_haul) has
-- been staged from the flight DB (seed_mesh.sh does the cross-database pipe:
-- short_haul = block time under 4 hours). Run against skybook_inventory.
-- Additive: existing flight_inventory rows (with live holds/reservations)
-- are never touched.
INSERT INTO flight_inventory
  (created_at, updated_at, created_by, version, flight_id, aircraft_id, status,
   total_seats, available_seats, held_seats, reserved_seats, blocked_seats)
SELECT now(), now(), 'data-seed-mesh', 0, t.flight_id,
  CASE WHEN t.short_haul
       THEN (SELECT id FROM aircraft WHERE registration_number='G-SKYA')   -- A320neo
       ELSE (SELECT id FROM aircraft WHERE registration_number='G-SKYB') END -- 777-300ER
  , 'OPEN',
  CASE WHEN t.short_haul THEN 180 ELSE 300 END,
  CASE WHEN t.short_haul THEN 180 ELSE 300 END,
  0, 0, 0
FROM tmp_all_flights t
WHERE NOT EXISTS (SELECT 1 FROM flight_inventory fi WHERE fi.flight_id = t.flight_id);

DROP TABLE tmp_all_flights;

SELECT count(*) AS flight_inventory_rows FROM flight_inventory;
