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

# The container is a parameter because the environment ladder runs one
# Postgres per compose project (skybook-dev-postgres-1, skybook-qa-postgres-1,
# ...); the default keeps the local developer invocation unchanged.
C="${1:-skybook-postgres-1}"
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

echo "4/5  flight_inventory (skybook_inventory): one record per flight"
docker exec -i "$C" psql -U postgres -d skybook_inventory -v ON_ERROR_STOP=1 < "$DIR/03_flight_inventory.sql"

echo "5/5  full-mesh densification (every pair, 3x daily, +1 year from today)"
bash "$DIR/seed_mesh.sh" "$C"

echo "done."

echo "terminals: real per-carrier terminal assignments (08_terminals.sql)"
docker exec -i "$C" psql -U postgres -d skybook_flight -v ON_ERROR_STOP=1 < "$DIR/08_terminals.sql"

echo "modern fleet + re-fleet (10_modern_fleet.sql, refleet.sh)"
docker exec -i "$C" psql -U postgres -d skybook_inventory -v ON_ERROR_STOP=1 < "$DIR/10_modern_fleet.sql"
bash "$DIR/refleet.sh" "$C"

# Seeded schedules author arrivals on the origin's clock; the platform stores
# them destination-local (scripts/fix-arrival-times-to-destination-local.sql).
# Chained here so EVERY freshly seeded environment - local re-seed or ladder
# rung - comes up with correct arrival boards, not just the ones someone
# remembered to fix. The script records itself, so this is a no-op on a
# database it already corrected.
echo "arrival times: re-expressing cross-timezone arrivals on the destination clock"
docker exec -i "$C" psql -U postgres -d skybook_flight -v ON_ERROR_STOP=1 \
  < "$DIR/../fix-arrival-times-to-destination-local.sql"
