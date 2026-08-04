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

export IMAGE_TAG="$TAG"
"${COMPOSE[@]}" pull -q
"${COMPOSE[@]}" up -d --no-build

# Health, then the same smoke DEV ran - but on this host, this architecture,
# these secrets.
bash .github/scripts/wait-healthy.sh skybook-staging 420
bash scripts/seed/seed.sh skybook-staging-postgres-1
bash .github/scripts/smoke.sh "http://127.0.0.1:${STAGING_GATEWAY_PORT:-8180}" \
                              "http://127.0.0.1:${STAGING_FRONTEND_PORT:-3100}"
echo "staging: rehearsal PASSED on the production host"
