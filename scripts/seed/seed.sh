#!/usr/bin/env bash
# Re-seed the Docker databases with a full year of bookable flights, a small
# fleet, seat maps, and per-flight inventory. Idempotent-ish: it REPLACES the
# flights and inventory tables (see 01_flights.sql / 02_aircraft_seats.sql).
#
# Prereqs: the compose stack is up (`docker compose up -d`) so the postgres
# container exists. Run from the repo root:
#
#   bash scripts/seed/seed.sh
#
# On Windows Git Bash, MSYS_NO_PATHCONV=1 stops path mangling in docker exec.
set -euo pipefail

C=skybook-postgres-1
DIR="$(cd "$(dirname "$0")" && pwd)"
export MSYS_NO_PATHCONV=1

echo "1/4  flights (skybook_flight): a year of daily departures for every route"
docker exec -i "$C" psql -U postgres -d skybook_flight -v ON_ERROR_STOP=1 < "$DIR/01_flights.sql"

echo "2/4  fleet + seat maps (skybook_inventory)"
docker exec -i "$C" psql -U postgres -d skybook_inventory -v ON_ERROR_STOP=1 < "$DIR/02_aircraft_seats.sql"

echo "3/4  stage flight ids across databases (same container, different DB)"
docker exec -i "$C" psql -U postgres -d skybook_inventory -c \
  "DROP TABLE IF EXISTS tmp_flights; CREATE TABLE tmp_flights(flight_id bigint, dest varchar(3));"
docker exec "$C" psql -U postgres -d skybook_flight -c \
  "COPY (SELECT id, destination_airport_code FROM flights) TO STDOUT" \
| docker exec -i "$C" psql -U postgres -d skybook_inventory -c "COPY tmp_flights FROM STDIN"

echo "4/4  flight_inventory (skybook_inventory): one record per flight"
docker exec -i "$C" psql -U postgres -d skybook_inventory -v ON_ERROR_STOP=1 < "$DIR/03_flight_inventory.sql"

echo "done."
