-- One FlightInventory per flight, keyed to the right aircraft by route type.
-- Assumes tmp_flights(flight_id, dest) has already been staged from the
-- flight DB (seed.sh does the cross-database pipe). Run against skybook_inventory.
INSERT INTO flight_inventory
  (created_at, updated_at, created_by, version, flight_id, aircraft_id, status,
   total_seats, available_seats, held_seats, reserved_seats, blocked_seats)
SELECT now(), now(), 'data-seed', 0, t.flight_id,
  CASE WHEN t.dest IN ('CDG','FRA','IST')
       THEN (SELECT id FROM aircraft WHERE registration_number='G-SKYA')   -- A320neo, short-haul
       ELSE (SELECT id FROM aircraft WHERE registration_number='G-SKYB') END -- 777-300ER, long-haul
  , 'OPEN',
  CASE WHEN t.dest IN ('CDG','FRA','IST') THEN 180 ELSE 300 END,   -- total
  CASE WHEN t.dest IN ('CDG','FRA','IST') THEN 180 ELSE 300 END,   -- available (all sellable initially)
  0, 0, 0
FROM tmp_flights t;

DROP TABLE tmp_flights;

SELECT count(*) AS flight_inventory_rows FROM flight_inventory;
