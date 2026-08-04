#!/usr/bin/env bash
# Per-run secrets for an ephemeral environment (dev/sit/qa/perf/uat/drill).
#
# Same pattern the nightly certification established: every secret the compose
# file requires is generated fresh for this run and thrown away with the
# runner. Nothing here is ever a real credential, which is exactly the point -
# an ephemeral environment that borrowed real secrets would leak them into
# logs and artifacts sooner or later.
set -euo pipefail

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem 2>/dev/null
openssl rsa -in private.pem -pubout -out public.pem 2>/dev/null
{
  echo "JWT_PRIVATE_KEY=$(grep -v 'PRIVATE KEY' private.pem | tr -d '\n')"
  echo "JWT_PUBLIC_KEY=$(grep -v 'PUBLIC KEY' public.pem | tr -d '\n')"
  echo "POSTGRES_PASSWORD=ci-postgres-$RANDOM$RANDOM"
  echo "MAIL_USERNAME=ci"
  echo "MAIL_PASSWORD=ci"
  echo "CHECKIN_BOARDING_PASS_KEY=ci-boarding-pass-key-32-bytes-minimum-ok"
  echo "GRAFANA_ADMIN_PASSWORD=ci-grafana-$RANDOM"
  echo "BOOKING_SERVICE_CLIENT_SECRET=ci-booking-$RANDOM"
  echo "CHECKIN_SERVICE_CLIENT_SECRET=ci-checkin-$RANDOM"
  echo "PAYMENT_SERVICE_CLIENT_SECRET=ci-payment-$RANDOM"
  echo "INVENTORY_SERVICE_CLIENT_SECRET=ci-inventory-$RANDOM"
} > .env
rm -f private.pem public.pem
echo "ephemeral secrets written to .env"
