#!/usr/bin/env bash
# ADDITIVE seed for the connections feature: hub-onward legs + their
# inventory, without touching the existing schedule or any booking's flight
# ids (unlike the full seed.sh, which replaces everything). Idempotent.
#
#   bash scripts/seed/seed_connections.sh
set -euo pipefail

C="${1:-skybook-postgres-1}"
DIR="$(cd "$(dirname "$0")" && pwd)"
export MSYS_NO_PATHCONV=1

echo "1/3  onward legs (skybook_flight)"
docker exec -i "$C" psql -U postgres -d skybook_flight -v ON_ERROR_STOP=1 < "$DIR/04_connection_legs.sql"

echo "2/3  stage NEW flight ids across databases"
# No -i: unfed stdin would swallow the calling script under `ssh bash -s`
# (see seed.sh for the full account).
docker exec "$C" psql -U postgres -d skybook_inventory -c \
  "DROP TABLE IF EXISTS tmp_flights; CREATE TABLE tmp_flights(flight_id bigint, dest varchar(3));"
docker exec "$C" psql -U postgres -d skybook_flight -c \
  "COPY (SELECT id, destination_airport_code FROM flights WHERE created_by = 'data-seed-connections') TO STDOUT" \
| docker exec -i "$C" psql -U postgres -d skybook_inventory -c "COPY tmp_flights FROM STDIN"

echo "3/3  inventory for the new legs only"
docker exec "$C" psql -U postgres -d skybook_inventory -v ON_ERROR_STOP=1 -c "
INSERT INTO flight_inventory
  (created_at, updated_at, created_by, version, flight_id, aircraft_id, status,
   total_seats, available_seats, held_seats, reserved_seats, blocked_seats)
SELECT now(), now(), 'data-seed-connections', 0, t.flight_id,
  CASE WHEN t.dest IN ('CDG','FRA','IST')
       THEN (SELECT id FROM aircraft WHERE registration_number='G-SKYA')
       ELSE (SELECT id FROM aircraft WHERE registration_number='G-SKYB') END,
  'OPEN',
  CASE WHEN t.dest IN ('CDG','FRA','IST') THEN 180 ELSE 300 END,
  CASE WHEN t.dest IN ('CDG','FRA','IST') THEN 180 ELSE 300 END,
  0, 0, 0
FROM tmp_flights t
WHERE NOT EXISTS (SELECT 1 FROM flight_inventory fi WHERE fi.flight_id = t.flight_id);
DROP TABLE tmp_flights;
SELECT count(*) AS connection_inventory_rows FROM flight_inventory WHERE created_by = 'data-seed-connections';
"

echo "done."

echo "terminals: real per-carrier terminal assignments (08_terminals.sql)"
docker exec -i "$C" psql -U postgres -d skybook_flight -v ON_ERROR_STOP=1 < "$DIR/08_terminals.sql"
