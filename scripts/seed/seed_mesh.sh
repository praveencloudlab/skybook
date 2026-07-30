#!/usr/bin/env bash
# Full-mesh densification: every directed airport pair gets 3 daily SkyBook
# Air (SB) departures for a year from TODAY, plus inventory for every flight
# that lacks it. ADDITIVE + idempotent - safe on a live stack with bookings,
# safe to re-run (it only fills gaps), and date-relative so a fresh install
# gets install-day -> +1 year.
#
# Prereqs: compose stack up. Run from the repo root:
#
#   bash scripts/seed/seed_mesh.sh
set -euo pipefail

C=skybook-postgres-1
DIR="$(cd "$(dirname "$0")" && pwd)"
export MSYS_NO_PATHCONV=1

echo "1/3  SB full-mesh flights (skybook_flight): 380 pairs x 3 daily x 366 days"
docker exec -i "$C" psql -U postgres -d skybook_flight -v ON_ERROR_STOP=1 < "$DIR/06_full_mesh.sql"

echo "2/3  stage flight ids + haul class across databases"
docker exec -i "$C" psql -U postgres -d skybook_inventory -c \
  "DROP TABLE IF EXISTS tmp_all_flights; CREATE TABLE tmp_all_flights(flight_id bigint, short_haul boolean);"
docker exec "$C" psql -U postgres -d skybook_flight -c \
  "COPY (SELECT id, (arrival_time - departure_time) < INTERVAL '4 hours' FROM flights) TO STDOUT" \
| docker exec -i "$C" psql -U postgres -d skybook_inventory -c "COPY tmp_all_flights FROM STDIN"

echo "3/3  inventory for flights that lack it (skybook_inventory)"
docker exec -i "$C" psql -U postgres -d skybook_inventory -v ON_ERROR_STOP=1 < "$DIR/07_mesh_inventory.sql"

echo "done."
