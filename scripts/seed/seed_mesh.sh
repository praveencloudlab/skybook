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

C="${1:-skybook-postgres-1}"
DIR="$(cd "$(dirname "$0")" && pwd)"
export MSYS_NO_PATHCONV=1

echo "1/3  SB full-mesh flights (skybook_flight): 380 pairs x 3 daily x 366 days"
docker exec -i "$C" psql -U postgres -d skybook_flight -v ON_ERROR_STOP=1 < "$DIR/06_full_mesh.sql"

echo "2/3  stage flight ids + haul class across databases"
# No -i: unfed stdin would swallow the calling script under `ssh bash -s`
# (see seed.sh for the full account).
docker exec "$C" psql -U postgres -d skybook_inventory -c \
  "DROP TABLE IF EXISTS tmp_all_flights; CREATE TABLE tmp_all_flights(flight_id bigint, short_haul boolean);"
# Haul class = BLOCK time < 4h. Arrivals are stored destination-local, so the
# raw column difference is block time +/- the zone offset (a JFK->LAX 5h30m
# flight reads 2h30m and would seed as short-haul). Resolve each end on its
# own zone (mirror of AirportTimeZones.java) before comparing.
docker exec "$C" psql -U postgres -d skybook_flight -c \
  "COPY (
     WITH zones(code, zone) AS (VALUES
       ('ATL','America/New_York'), ('JFK','America/New_York'), ('MIA','America/New_York'),
       ('ORD','America/Chicago'),  ('DFW','America/Chicago'),
       ('LAX','America/Los_Angeles'), ('SFO','America/Los_Angeles'),
       ('LHR','Europe/London'), ('MAN','Europe/London'), ('BHX','Europe/London'),
       ('EDI','Europe/London'), ('GLA','Europe/London'),
       ('CDG','Europe/Paris'), ('FRA','Europe/Berlin'), ('IST','Europe/Istanbul'),
       ('JNB','Africa/Johannesburg'), ('NBO','Africa/Nairobi'),
       ('DXB','Asia/Dubai'), ('AUH','Asia/Dubai'), ('DOH','Asia/Qatar'),
       ('BOM','Asia/Kolkata'), ('DEL','Asia/Kolkata'), ('HYD','Asia/Kolkata'),
       ('MAA','Asia/Kolkata'), ('BLR','Asia/Kolkata'), ('CCU','Asia/Kolkata'),
       ('HKG','Asia/Hong_Kong'), ('SIN','Asia/Singapore'), ('SYD','Australia/Sydney'))
     SELECT f.id,
            ((f.arrival_time AT TIME ZONE COALESCE(dz.zone,'UTC'))
           - (f.departure_time AT TIME ZONE COALESCE(oz.zone,'UTC'))) < INTERVAL '4 hours'
     FROM flights f
     LEFT JOIN zones oz ON oz.code = f.origin_airport_code
     LEFT JOIN zones dz ON dz.code = f.destination_airport_code
   ) TO STDOUT" \
| docker exec -i "$C" psql -U postgres -d skybook_inventory -c "COPY tmp_all_flights FROM STDIN"

echo "3/3  inventory for flights that lack it (skybook_inventory)"
docker exec -i "$C" psql -U postgres -d skybook_inventory -v ON_ERROR_STOP=1 < "$DIR/07_mesh_inventory.sql"

echo "done."

echo "terminals: real per-carrier terminal assignments (08_terminals.sql)"
docker exec -i "$C" psql -U postgres -d skybook_flight -v ON_ERROR_STOP=1 < "$DIR/08_terminals.sql"

echo "modern fleet + re-fleet (10_modern_fleet.sql, refleet.sh)"
docker exec -i "$C" psql -U postgres -d skybook_inventory -v ON_ERROR_STOP=1 < "$DIR/10_modern_fleet.sql"
bash "$DIR/refleet.sh" "$C"
