#!/usr/bin/env bash
# Restore a scripts/backup.sh archive and PROVE it (docs/DR_RUNBOOK.md).
#
#   scripts/restore.sh <archive.tar.gz> [container]
#
# Restores every database dump in the archive into the target Postgres
# container with --clean --if-exists (drop and recreate objects), then
# re-counts every database and compares against the MANIFEST written at
# backup time. Exit 0 means "the data demonstrably came back", not "the
# commands ran" - a restore that cannot say how many rows it restored is a
# hope, not a recovery.
#
# Deliberately takes the container as a parameter: the weekly drill restores
# into a scratch Postgres, never the live one, and a real recovery names the
# new container explicitly. There is no default-to-production footgun here.
set -euo pipefail

ARCHIVE="${1:?usage: restore.sh <archive.tar.gz> [container]}"
CONTAINER="${2:?name the target postgres container explicitly}"

COUNT_SQL="SELECT COALESCE(SUM(cnt),0) FROM (
  SELECT (xpath('/row/cnt/text()',
                query_to_xml(format('SELECT count(*) AS cnt FROM %I.%I', schemaname, tablename),
                             false, true, '')))[1]::text::bigint AS cnt
    FROM pg_tables WHERE schemaname='public') t;"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
tar -xzf "$ARCHIVE" -C "$WORK"
[ -f "$WORK/MANIFEST" ] || { echo "restore: archive has no MANIFEST - refusing to restore what cannot be verified"; exit 1; }

FAILED=0
while read -r db expected; do
  echo "restore: ${db} (expecting ${expected} rows)"
  docker exec "$CONTAINER" psql -U postgres -tA -c \
    "SELECT 1 FROM pg_database WHERE datname='${db}'" | grep -q 1 \
    || docker exec "$CONTAINER" psql -U postgres -c "CREATE DATABASE ${db}"
  docker exec -i "$CONTAINER" pg_restore -U postgres --clean --if-exists \
    --no-owner -d "$db" < "$WORK/${db}.dump"
  ACTUAL=$(docker exec "$CONTAINER" psql -U postgres -d "$db" -tA -c "$COUNT_SQL")
  if [ "$ACTUAL" = "$expected" ]; then
    echo "  verified: ${ACTUAL} rows"
  else
    echo "  MISMATCH: restored ${ACTUAL}, manifest says ${expected}"
    FAILED=1
  fi
done < "$WORK/MANIFEST"

if [ "$FAILED" -ne 0 ]; then
  echo "restore: FAILED verification"
  exit 1
fi
echo "restore: every database verified against the manifest"
