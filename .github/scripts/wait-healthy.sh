#!/usr/bin/env bash
# Block until every container in the project is past 'starting' and none is
# 'unhealthy' - or fail loudly with the ps table, because a rung that
# proceeds against a half-up stack produces failures that blame the wrong
# layer.
#
#   wait-healthy.sh <compose-project> [timeout-seconds]
set -euo pipefail

PROJECT="${1:?compose project name}"
TIMEOUT="${2:-360}"
DEADLINE=$(( $(date +%s) + TIMEOUT ))

while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  STATUSES="$(docker compose -p "$PROJECT" ps --format '{{.Status}}' || true)"
  STARTING=$(grep -c 'starting' <<< "$STATUSES" || true)
  UNHEALTHY=$(grep -c 'unhealthy' <<< "$STATUSES" || true)
  if [ "$STARTING" = "0" ] && [ "$UNHEALTHY" = "0" ] && [ -n "$STATUSES" ]; then
    echo "wait-healthy: ${PROJECT} settled"
    docker compose -p "$PROJECT" ps --format '{{.Name}}\t{{.Status}}' | sort
    exit 0
  fi
  sleep 5
done

echo "wait-healthy: ${PROJECT} did NOT settle within ${TIMEOUT}s"
docker compose -p "$PROJECT" ps --format '{{.Name}}\t{{.Status}}' | sort
exit 1
