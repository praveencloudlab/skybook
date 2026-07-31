#!/usr/bin/env bash
# Re-fleet the schedule onto the modern aircraft (09_refleet.sql): stages
# flight routes into the inventory database, then reassigns each UNTOUCHED
# flight inventory (no holds, no reservations) to the aircraft its mission
# calls for. Safe to re-run any time; flights with sold seats keep their metal.
set -euo pipefail

C=skybook-postgres-1
DIR="$(cd "$(dirname "$0")" && pwd)"
export MSYS_NO_PATHCONV=1

echo "1/2  stage flight routes into skybook_inventory"
docker exec "$C" psql -U postgres -d skybook_inventory -c \
  "DROP TABLE IF EXISTS tmp_refleet; CREATE TABLE tmp_refleet(flight_id bigint, origin varchar(3), dest varchar(3));"
docker exec "$C" psql -U postgres -d skybook_flight -c \
  "COPY (SELECT id, origin_airport_code, destination_airport_code FROM flights WHERE status = 'SCHEDULED') TO STDOUT" \
| docker exec -i "$C" psql -U postgres -d skybook_inventory -c "COPY tmp_refleet FROM STDIN"

echo "2/2  re-fleet untouched inventories (09_refleet.sql)"
docker exec -i "$C" psql -U postgres -d skybook_inventory -v ON_ERROR_STOP=1 < "$DIR/09_refleet.sql"
docker exec "$C" psql -U postgres -d skybook_inventory -c \
  "SELECT a.registration_number, a.model, count(*) AS flights FROM flight_inventory fi JOIN aircraft a ON a.id = fi.aircraft_id GROUP BY 1, 2 ORDER BY 3 DESC;"
