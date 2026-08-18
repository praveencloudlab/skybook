#!/usr/bin/env bash
# India domestic network: fleet with honest cabins, a year of dated
# departures for every served Indian city, and per-flight hull assignment.
# ADDITIVE + idempotent (NOT EXISTS-guarded everywhere) - safe on a live
# stack with bookings, safe to re-run. Regenerate the SQL from the one
# airports table with:  node scripts/seed/gen_india_network.mjs
#
# Prereqs: compose stack up. Run from the repo root:
#
#   bash scripts/seed/seed_india.sh
set -euo pipefail

C="${1:-skybook-postgres-1}"
DIR="$(cd "$(dirname "$0")" && pwd)"
export MSYS_NO_PATHCONV=1

echo "1/3  India fleet + seat maps (skybook_inventory): ATR/A320/737 honest cabins"
docker exec -i "$C" psql -U postgres -d skybook_inventory -v ON_ERROR_STOP=1 < "$DIR/13_india_fleet.sql"

echo "2/3  India flights (skybook_flight): 540 daily departures, a year from today"
docker exec -i "$C" psql -U postgres -d skybook_flight -v ON_ERROR_STOP=1 < "$DIR/12_india_flights.sql"

echo "3/3  stage india flight ids + per-flight hulls (skybook_inventory)"
# No -i on unfed execs - an attached stdin under `ssh bash -s` eats the rest
# of the calling script (see seed.sh for the account).
docker exec "$C" psql -U postgres -d skybook_inventory -c \
  "DROP TABLE IF EXISTS tmp_india_flights; CREATE TABLE tmp_india_flights(flight_id bigint, flight_number varchar(10));"
docker exec "$C" psql -U postgres -d skybook_flight -c \
  "COPY (SELECT id, flight_number FROM flights WHERE created_by = 'data-seed-india') TO STDOUT" \
| docker exec -i "$C" psql -U postgres -d skybook_inventory -c "COPY tmp_india_flights FROM STDIN"
docker exec -i "$C" psql -U postgres -d skybook_inventory -v ON_ERROR_STOP=1 < "$DIR/14_india_inventory.sql"

echo "done."
