#!/usr/bin/env bash
# SIT: do the promoted services agree with EACH OTHER?
#
# Every probe here crosses at least one boundary between services - that is
# the entire point of the rung. Unit suites proved each service alone; QA
# will prove the customer journey; SIT sits between them and answers the
# integration question as directly as possible:
#
#   gateway -> flight-service        (routing + public-path fan-out)
#   booking-service -> flight-service (the quote calls across with a service token)
#   auth -> Kafka -> notification -> Mailpit  (an event traverses the bus and
#                                     becomes an email in the sink)
set -euo pipefail

GATEWAY="http://localhost:8080"
MAILPIT="http://localhost:8025"
DATE="$(date -u -d '+14 days' +%Y-%m-%d)"

echo "sit: gateway routes to flight-service, tokenless read allowed"
ITIN=$(curl -sf "$GATEWAY/api/flights/itineraries?originAirportCode=LHR&destinationAirportCode=DXB&departureDate=$DATE")
echo "$ITIN" | grep -q '"legs"' || { echo "sit: no itineraries - seed or routing broken"; exit 1; }

echo "sit: booking-service reaches flight-service with its own service token"
FLIGHT_ID=$(echo "$ITIN" | grep -oE '"id":[0-9]+' | head -1 | cut -d: -f2)
curl -sf -X POST "$GATEWAY/api/bookings/quote" -H 'Content-Type: application/json' \
    -d "{\"flightId\":$FLIGHT_ID}" | grep -qi 'fare' \
  || { echo "sit: quote failed - the service-to-service credential path is broken"; exit 1; }

echo "sit: an auth event crosses Kafka and lands in the mail sink"
WHO="sit-$RANDOM-$(date +%s)@skybook.ci"
curl -sf -X POST "$GATEWAY/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"fullName\":\"Sit Probe\",\"email\":\"$WHO\",\"password\":\"SitLadder#2026x\"}" > /dev/null
curl -sf -X POST "$GATEWAY/api/auth/forgot-password" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$WHO\"}" > /dev/null
for i in $(seq 1 30); do
  TOTAL=$(curl -sf "$MAILPIT/api/v1/search?query=to:$WHO" | grep -o '"total":[0-9]*' | head -1 | cut -d: -f2 || echo 0)
  [ "${TOTAL:-0}" -ge 1 ] && { echo "sit: reset email arrived via bus + sink after ${i} poll(s)"; break; }
  [ "$i" = "30" ] && { echo "sit: no email in the sink after 60s - bus or consumer broken"; exit 1; }
  sleep 2
done

echo "sit: PASSED"
