#!/usr/bin/env bash
# Runs ON the VM over SSH: the production promotion itself.
#
#   bash prod-on-vm.sh <image-tag>
#
# Pulls the certified digests, rolls the stack, verifies health through the
# real front door, and finishes with a backup - so the newest restore point
# always brackets the newest release, and a bad deploy can be rolled back to
# the state immediately before it (DR_RUNBOOK.md §4).
set -euo pipefail

TAG="${1:?image tag}"
cd ~/skybook

git fetch origin main --quiet
git checkout --quiet "$TAG"

COMPOSE=(docker compose
  --env-file .env
  --env-file deploy/environments/prod.env
  -f docker-compose.yml -f docker-compose.ladder.yml -f docker-compose.prod.yml)

export IMAGE_TAG="$TAG"
"${COMPOSE[@]}" pull -q
"${COMPOSE[@]}" up -d --no-build --remove-orphans

bash .github/scripts/wait-healthy.sh skybook 420

# Through the real door: Caddy terminates TLS for SKYBOOK_DOMAIN, so hitting
# it exercises certificate, proxy and application in one probe.
DOMAIN="$(grep -oP '^SKYBOOK_DOMAIN=\K.*' .env || true)"
if [ -n "$DOMAIN" ]; then
  for i in $(seq 1 12); do
    curl -sf -o /dev/null "https://${DOMAIN}/" && { echo "prod: front door answers on https://${DOMAIN}"; break; }
    [ "$i" = "12" ] && { echo "prod: front door did NOT answer"; exit 1; }
    sleep 10
  done
fi

echo "prod: taking the post-deploy backup"
bash scripts/backup.sh ./backups skybook-postgres-1 14
echo "prod: promotion of ${TAG} complete"
