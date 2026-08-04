#!/usr/bin/env bash
# Nightly logical backup of every SkyBook database (docs/DR_RUNBOOK.md).
#
#   scripts/backup.sh [output-dir] [container] [retention-days]
#
# Produces one timestamped archive containing a pg_dump (custom format,
# compressed) per database, plus a MANIFEST of exact per-database row counts
# so a restore can be VERIFIED against what was backed up rather than merely
# declared done. Custom format rather than plain SQL because it restores with
# pg_restore --clean --if-exists, which makes the weekly drill idempotent.
#
# Keeps the newest N days of archives. The cadence sets the RPO: nightly on
# the VM means up to 24 hours of bookings are at risk, and that number is a
# stated decision (DR_RUNBOOK.md §2), not an accident.
set -euo pipefail

OUT_DIR="${1:-./backups}"
CONTAINER="${2:-skybook-postgres-1}"
RETENTION_DAYS="${3:-14}"
DATABASES=(skybook_auth skybook_flight skybook_booking skybook_inventory skybook_payment skybook_checkin)

# Exact row total across public tables - reltuples is an estimate and an
# estimate cannot verify a restore.
COUNT_SQL="SELECT COALESCE(SUM(cnt),0) FROM (
  SELECT (xpath('/row/cnt/text()',
                query_to_xml(format('SELECT count(*) AS cnt FROM %I.%I', schemaname, tablename),
                             false, true, '')))[1]::text::bigint AS cnt
    FROM pg_tables WHERE schemaname='public') t;"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT_DIR"

echo "backup: ${STAMP} from ${CONTAINER}"
for db in "${DATABASES[@]}"; do
  docker exec "$CONTAINER" pg_dump -U postgres -Fc -Z6 -d "$db" > "$WORK/${db}.dump"
  ROWS=$(docker exec "$CONTAINER" psql -U postgres -d "$db" -tA -c "$COUNT_SQL")
  echo "${db} ${ROWS}" >> "$WORK/MANIFEST"
  echo "  ${db}: ${ROWS} rows"
done

ARCHIVE="$OUT_DIR/skybook-${STAMP}.tar.gz"
tar -czf "$ARCHIVE" -C "$WORK" .
echo "backup: wrote ${ARCHIVE} ($(du -h "$ARCHIVE" | cut -f1))"

find "$OUT_DIR" -name 'skybook-*.tar.gz' -mtime +"$RETENTION_DAYS" -delete
echo "backup: retention ${RETENTION_DAYS}d applied"
