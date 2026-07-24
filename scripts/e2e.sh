#!/usr/bin/env bash
#
# One command that proves the platform works (E2E_CERTIFICATION_MODULE.md §9).
#
#   ./scripts/e2e.sh
#
# Brings the fleet up WITH the e2e overrides, seeds if needed, runs the
# certification suite, and reports. Safe to re-run: the suite isolates itself by
# creating fresh identities rather than resetting shared state.
#
# Required (an ADMIN cannot be granted through any API - see §4):
#   E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD  - credentials of an account that
#   SKYBOOK_BOOTSTRAP_ADMIN_EMAIL has promoted. If the account does not exist
#   yet: register it, set that variable in .env, then recreate auth-service.
#
set -euo pipefail

cd "$(dirname "$0")/.."

COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.e2e.yml)

if [[ -z "${E2E_ADMIN_EMAIL:-}" || -z "${E2E_ADMIN_PASSWORD:-}" ]]; then
    cat >&2 <<'EOF'
ERROR: E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD must be set.

The suite drives ADMIN-only back-office assertions, and ADMIN is granted only by
auth-service at startup to the address in SKYBOOK_BOOTSTRAP_ADMIN_EMAIL - there
is no API for it. To set one up:

  1. register the account through the gateway
  2. set SKYBOOK_BOOTSTRAP_ADMIN_EMAIL=<that address> in .env
  3. docker compose up -d auth-service      # promotion happens once, at boot
  4. export E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD, then re-run
EOF
    exit 2
fi

echo "==> Bringing up the fleet with e2e overrides"
"${COMPOSE[@]}" up -d

echo "==> Waiting for containers to report healthy"
for _ in $(seq 1 60); do
    starting="$("${COMPOSE[@]}" ps --format '{{.Status}}' | grep -c 'starting' || true)"
    [[ "$starting" == "0" ]] && break
    sleep 5
done
"${COMPOSE[@]}" ps --format '{{.Name}}\t{{.Status}}' | sort

# The suite's preflight checks this too, but seeding here turns a confusing test
# failure into something the script just fixes. Asked of the database directly
# rather than the API, because the API needs a token we do not have yet - and
# re-seeding 10,950 flights on every run would be needlessly slow.
echo "==> Checking seed data"
FLIGHTS="$(docker compose exec -T postgres \
    psql -U postgres -d skybook_flight -tAc 'select count(*) from flights' 2>/dev/null | tr -d '[:space:]' || echo 0)"

if [[ "${FLIGHTS:-0}" -lt 1 ]]; then
    echo "    no flights found - seeding"
    ./scripts/seed/seed.sh
else
    echo "    $FLIGHTS flights already present - skipping seed"
fi

echo "==> Running the certification suite"
cd backend
mvn -pl e2e-tests -Pe2e verify \
    "-De2e.admin.email=${E2E_ADMIN_EMAIL}" \
    "-De2e.admin.password=${E2E_ADMIN_PASSWORD}" \
    "-De2e.baseUrl=${E2E_BASE_URL:-http://localhost:8080}"

echo
echo "==> Certified. Reports: backend/e2e-tests/target/failsafe-reports/"
