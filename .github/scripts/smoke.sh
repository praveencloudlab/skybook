#!/usr/bin/env bash
# The DEV smoke: does the promoted artifact answer its front door?
#
#   smoke.sh <gateway-base> <frontend-base>
#
# Three claims, each cheap and each meaningful:
#   1. the SPA is served;
#   2. anonymous shopping works END TO END (gateway -> flight-service -> data),
#      and actually returns itineraries, not just a 200 - an empty search
#      after seeding means the environment is broken in a way a status code
#      would hide;
#   3. the write path works: a fresh account can register and sign in.
set -euo pipefail

GATEWAY="${1:?gateway base url}"
FRONTEND="${2:?frontend base url}"
DATE="$(date -u -d '+14 days' +%Y-%m-%d 2>/dev/null || date -u -v+14d +%Y-%m-%d)"

echo "smoke: frontend serves"
curl -sf -o /dev/null "$FRONTEND/"

echo "smoke: anonymous search returns real itineraries"
BODY=$(curl -sf "$GATEWAY/api/flights/itineraries?originAirportCode=LHR&destinationAirportCode=DXB&departureDate=$DATE")
COUNT=$(node -e "const a=JSON.parse(process.argv[1]);console.log(Array.isArray(a)?a.length:0)" "$BODY" 2>/dev/null \
  || echo "$BODY" | grep -o '"legs"' | wc -l)
if [ "${COUNT:-0}" -lt 1 ]; then
  echo "smoke: search answered but carried no itineraries - seeding failed?"
  exit 1
fi
echo "smoke: ${COUNT} itinerary option(s) for LHR->DXB on $DATE"

echo "smoke: register + login round trip"
WHO="smoke-$RANDOM-$(date +%s)@skybook.ci"
curl -sf -X POST "$GATEWAY/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"fullName\":\"Smoke Test\",\"email\":\"$WHO\",\"password\":\"SmokeLadder#2026x\"}" > /dev/null
TOKEN=$(curl -sf -X POST "$GATEWAY/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$WHO\",\"password\":\"SmokeLadder#2026x\"}" )
[ -n "$TOKEN" ] || { echo "smoke: login returned no token"; exit 1; }
curl -sf -H "Authorization: Bearer $TOKEN" "$GATEWAY/api/auth/me" > /dev/null
echo "smoke: PASSED"
