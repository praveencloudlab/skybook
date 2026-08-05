#!/usr/bin/env bash
# Runs ON the VM over SSH: the transient staging rehearsal.
#
#   bash staging-on-vm.sh <image-tag>
#
# Stands the promoted images up as a SECOND compose project beside prod,
# smokes them through the staging ports, and tears the rehearsal down again
# win or lose - a one-VM estate cannot afford a standing twin, and a rehearsal
# left running would quietly eat the memory prod needs
# (docker-compose.staging.yml states the trade).
set -euo pipefail

TAG="${1:?image tag}"
cd ~/skybook

git fetch origin main --quiet
git checkout --quiet "$TAG"

COMPOSE=(docker compose
  --env-file .env
  --env-file deploy/environments/staging.env
  -f docker-compose.yml -f docker-compose.ladder.yml -f docker-compose.staging.yml)

cleanup() {
  echo "staging: tearing the rehearsal down"
  "${COMPOSE[@]}" down -v --remove-orphans || true
}
trap cleanup EXIT

# The rehearsal is the APPLICATION chain only. The observability containers
# bind-mount ./docker-data/* on the host, and those directories belong to the
# live prod project - the first full walk proved it when staging's prometheus
# died on prod's TSDB lock and depends_on took the whole rehearsal with it.
# Postgres and kafka state is isolated by the staging overlay (named volumes,
# project-scoped); grafana/prometheus/loki/tempo/promtail are simply never
# started - nobody dashboards an environment that lives for four minutes,
# and on a 16 GB host the memory is better spent on the thing being tested.
APP_SERVICES=(postgres kafka mailpit otel-agent
  auth-service flight-service booking-service inventory-service
  payment-service checkin-service notification-service
  api-gateway frontend)

export IMAGE_TAG="$TAG"
"${COMPOSE[@]}" pull -q "${APP_SERVICES[@]}"
"${COMPOSE[@]}" up -d --no-build "${APP_SERVICES[@]}"

# Health, then the same smoke DEV ran - but on this host, this architecture,
# these secrets.
#
# The </dev/null matters: this script arrives over `ssh bash -s`, so ITS OWN
# remaining lines are what's on stdin. Any child that drains stdin (one
# docker exec -i was enough) truncates the run at wherever it had read to -
# and bash then exits 0 at the phantom EOF, a green light with the smoke
# test silently gone. The seed scripts no longer attach stdin anywhere, but
# the rehearsal must not be one regression away from passing vacuously.
bash .github/scripts/wait-healthy.sh skybook-staging 420 < /dev/null
bash scripts/seed/seed.sh skybook-staging-postgres-1 < /dev/null
bash .github/scripts/smoke.sh "http://127.0.0.1:${STAGING_GATEWAY_PORT:-8180}" \
                              "http://127.0.0.1:${STAGING_FRONTEND_PORT:-3900}"
echo "staging: rehearsal PASSED on the production host"
