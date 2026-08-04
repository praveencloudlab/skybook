#!/usr/bin/env bash
# UAT acceptance journey - the business path, end to end, with evidence.
#
# Mirrors the certified customer journey call-for-call: search anonymously,
# create an account, book a real seat, watch the payment row appear off the
# bus, authorize, capture, and poll the booking to CONFIRMED. Every line of
# output is evidence for the human approving the 'uat' environment gate -
# the sign-off is against this transcript, not against a feeling.
set -euo pipefail

GATEWAY="${1:-http://localhost:8080}"
DATE="$(date -u -d '+21 days' +%Y-%m-%d)"
WHO="uat-$RANDOM-$(date +%s)@skybook.ci"
PW='UatLadder#2026x'
J() { grep -o "\"$2\":$3" <<< "$1" | head -1 | cut -d: -f2- | tr -d '"'; }

echo "UAT evidence — $(date -u +%FT%TZ)"
echo "1. anonymous search LHR->DXB on ${DATE}"
ITIN=$(curl -sf "$GATEWAY/api/flights/itineraries?originAirportCode=LHR&destinationAirportCode=DXB&departureDate=$DATE")
FLIGHT_ID=$(grep -oE '"id":[0-9]+' <<< "$ITIN" | head -1 | cut -d: -f2)
echo "   flight id ${FLIGHT_ID} offered without a session"

echo "2. account created and signed in (${WHO})"
curl -sf -X POST "$GATEWAY/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"fullName\":\"UAT Acceptance\",\"email\":\"$WHO\",\"password\":\"$PW\"}" > /dev/null
TOKEN=$(curl -sf -X POST "$GATEWAY/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$WHO\",\"password\":\"$PW\"}" )
[ -n "$TOKEN" ] || { echo "   FAILED: no token"; exit 1; }
AUTH=(-H "Authorization: Bearer $TOKEN")

echo "3. booking created (ECONOMY/SAVER, auto-assigned seat)"
BOOKING=$(curl -sf -X POST "$GATEWAY/api/bookings" "${AUTH[@]}" -H 'Content-Type: application/json' -d "{
  \"customerId\":9001,\"flightId\":${FLIGHT_ID},
  \"passengers\":[{\"title\":\"Ms\",\"firstName\":\"Uat\",\"lastName\":\"Acceptance\",\"dob\":\"1991-04-02\",
    \"nationality\":\"GBR\",\"passportNumber\":\"U${RANDOM}X\",\"passportExpiry\":\"2032-01-01\",
    \"travelClass\":\"ECONOMY\",\"fareType\":\"SAVER\"}],
  \"contact\":{\"contactName\":\"Uat Acceptance\",\"contactEmail\":\"$WHO\",\"contactPhone\":\"+44 7700 900123\"}}")
BOOKING_ID=$(J "$BOOKING" id '[0-9]*')
PNR=$(J "$BOOKING" bookingReference '"[A-Z0-9]*"')
echo "   booking ${BOOKING_ID}, record locator ${PNR}"

echo "4. payment row appears via the bus"
for i in $(seq 1 30); do
  PAYMENT=$(curl -s "$GATEWAY/api/payments/booking/$BOOKING_ID" "${AUTH[@]}")
  PAYMENT_ID=$(J "$PAYMENT" id '[0-9]*')
  [ -n "$PAYMENT_ID" ] && { echo "   payment ${PAYMENT_ID} created by the consumer after ${i} poll(s)"; break; }
  [ "$i" = "30" ] && { echo "   FAILED: no payment row after 60s"; exit 1; }
  sleep 2
done

echo "5. authorize + capture"
curl -sf -X PATCH "$GATEWAY/api/payments/$PAYMENT_ID/authorize" "${AUTH[@]}" > /dev/null
curl -sf -X PATCH "$GATEWAY/api/payments/$PAYMENT_ID/capture" "${AUTH[@]}" > /dev/null
echo "   captured"

echo "6. booking reaches CONFIRMED off the payment event"
for i in $(seq 1 30); do
  STATUS=$(curl -s "$GATEWAY/api/bookings/$BOOKING_ID" "${AUTH[@]}" | grep -o '"bookingStatus":"[A-Z_]*"' | cut -d'"' -f4)
  [ "$STATUS" = "CONFIRMED" ] && { echo "   CONFIRMED after ${i} poll(s)"; break; }
  [ "$i" = "30" ] && { echo "   FAILED: status ${STATUS:-none} after 60s"; exit 1; }
  sleep 2
done

echo "7. cancellation preview prices the refund"
curl -sf "$GATEWAY/api/bookings/$BOOKING_ID/cancellation-preview" "${AUTH[@]}" \
  | grep -o '"refundAmount":[0-9.]*' | head -1 | sed 's/^/   /'

echo "UAT journey PASSED — approve the gate against this transcript"
