#!/usr/bin/env bash
# UK domestic + Crown Dependency network: BA two-class hulls (the only UK
# domestic Business), all-economy LCC/regional/island fleet, CAA-demand-derived
# frequencies, effective-window aware. ADDITIVE + idempotent. Regenerate SQL
# from the one route table with:  node scripts/seed/gen_uk_network.mjs
#
#   bash scripts/seed/seed_uk.sh
set -euo pipefail

C="${1:-skybook-postgres-1}"
DIR="$(cd "$(dirname "$0")" && pwd)"
export MSYS_NO_PATHCONV=1

echo "1/3  UK fleet + seat maps (skybook_inventory)"
docker exec -i "$C" psql -U postgres -d skybook_inventory -v ON_ERROR_STOP=1 < "$DIR/15_uk_fleet.sql"

echo "2/3  UK flights (skybook_flight): CAA-demand-derived daily departures"
docker exec -i "$C" psql -U postgres -d skybook_flight -v ON_ERROR_STOP=1 < "$DIR/16_uk_flights.sql"

echo "3/3  stage uk flight ids + per-flight hulls (skybook_inventory)"
# No -i on unfed execs (ssh bash -s stdin-swallowing; see seed.sh).
docker exec "$C" psql -U postgres -d skybook_inventory -c \
  "DROP TABLE IF EXISTS tmp_uk_flights; CREATE TABLE tmp_uk_flights(flight_id bigint, flight_number varchar(10));"
docker exec "$C" psql -U postgres -d skybook_flight -c \
  "COPY (SELECT id, flight_number FROM flights WHERE created_by = 'data-seed-uk') TO STDOUT" \
| docker exec -i "$C" psql -U postgres -d skybook_inventory -c "COPY tmp_uk_flights FROM STDIN"
docker exec -i "$C" psql -U postgres -d skybook_inventory -v ON_ERROR_STOP=1 < "$DIR/17_uk_inventory.sql"

echo "done."
