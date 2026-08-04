#!/usr/bin/env bash
# The bind-mount ownership fix, lifted verbatim from the nightly workflow.
#
# The observability services run as non-root (Prometheus as nobody, Loki as
# 10001, Grafana as 472) over ./docker-data bind mounts. On a developer
# machine those directories already exist writable; on a fresh runner Docker
# creates them ROOT-owned, Prometheus dies with "permission denied", and the
# rest of the fleet follows it down through depends_on. Only ever reproducible
# on a fresh machine - which is exactly what every rung of this ladder is.
set -euo pipefail
mkdir -p docker-data/{prometheus,loki,tempo,grafana,promtail,postgres,kafka}
chmod -R 777 docker-data
