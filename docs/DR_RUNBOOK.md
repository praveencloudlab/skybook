# Disaster Recovery Runbook

The DR rung of the environment ladder (ENVIRONMENTS.md). Everything here is
exercised by machinery, not just written down: the weekly drill in
`promote.yml` performs a full backup → destroy → restore → verify cycle in
public, and the restore script refuses to declare success without proving row
counts against the manifest taken at backup time.

## 1. What is protected, and by what

| Asset | Mechanism | Where |
|---|---|---|
| The six databases (auth, flight, booking, inventory, payment, checkin) | `scripts/backup.sh` — pg_dump custom format per DB + row-count manifest, one timestamped archive | `~/skybook/backups/` on the VM, 14-day retention |
| The application | Not backed up — rebuilt by construction. Every release is an immutable multi-arch image in GHCR addressed by commit SHA; the compose files and config are in git. | GHCR + GitHub |
| Secrets (`~/skybook/.env`: JWT keypair, DB password, mail creds, client secrets) | **Not** in any backup on purpose — a backup archive that contains the keys to itself is a liability. Kept as an offline copy by the operator (password manager). | operator |
| TLS certificate | Not backed up — Caddy re-obtains from Let's Encrypt on first boot at the same domain. | automatic |

## 2. RPO / RTO — the stated numbers

- **RPO: 24 hours** (worst case). Backups run nightly via cron plus after
  every production deploy, so the practical exposure is usually far smaller.
  This is a free-tier decision, stated rather than hidden: continuous
  archiving (WAL shipping) would need a second location and standing storage.
  Tightening RPO = raising the cron frequency; the script is idempotent and
  cheap (a full archive of 1.34M rows measured 3.8 MB).
- **RTO: ~30 minutes** on a fresh VM, dominated by image pulls; ~10 minutes
  if the VM survives and only data was lost. Both bounded by the drill below,
  which performs the same steps mechanically every week.

## 3. Nightly schedule (VM, one-time setup)

```bash
crontab -e
# nightly at 02:30 UTC, log to the journal
30 2 * * * cd ~/skybook && bash scripts/backup.sh ./backups skybook-postgres-1 14 >> backups/backup.log 2>&1
```

Off-VM copy (recommended, still free): sync `~/skybook/backups/` anywhere
off-host — even `rclone` to a free object-storage bucket — so the VM's disk is
not a single point of failure for its own restore points. Until that is set
up, the honest statement is: **the backups share the VM's fate**, and the
weekly drill only proves logical restorability, not host-loss survival.

## 4. Scenario A — bad deploy, data intact

Do not touch backups. Promote the previous artifact:
GitHub → Actions → *Promote* → Run workflow → `image_tag` = last good SHA.
The ladder re-runs every gate on the way back down to prod.

## 5. Scenario B — data corrupted or lost, VM alive

```bash
cd ~/skybook
ls backups/                                   # choose the restore point
docker compose --env-file .env --env-file deploy/environments/prod.env \
  -f docker-compose.yml -f docker-compose.ladder.yml -f docker-compose.prod.yml \
  stop api-gateway auth-service flight-service booking-service \
       inventory-service payment-service checkin-service notification-service
bash scripts/restore.sh backups/skybook-<STAMP>.tar.gz skybook-postgres-1
# restore.sh exits non-zero unless every row count matches the manifest
docker compose --env-file .env --env-file deploy/environments/prod.env \
  -f docker-compose.yml -f docker-compose.ladder.yml -f docker-compose.prod.yml \
  up -d
bash .github/scripts/smoke.sh http://127.0.0.1:8080 http://127.0.0.1:3000
```

What is lost: everything after the chosen archive (RPO). Bookings paid in the
gap will exist in customers' inboxes but not in the database — the operator
communication that follows is a business step, and pretending a runbook makes
it painless would be the kind of lie this project tries not to tell.

## 6. Scenario C — the VM is gone

1. Provision a replacement (docs/DEPLOY_ORACLE.md — the shape-hunting loop,
   Docker install, firewall). Point the domain's DNS at the new address; with
   sslip.io the new address *is* the new domain, and `SKYBOOK_DOMAIN` changes
   with it.
2. Recreate `~/skybook/.env` from the operator's offline secret copy.
3. `git clone` the repository; check out the SHA currently in production
   (visible in the last *Promote* run).
4. Fetch the newest backup archive from the off-VM copy (§3).
5. Bring up **postgres only**, restore, then the fleet:

```bash
cd ~/skybook
docker compose --env-file .env --env-file deploy/environments/prod.env \
  -f docker-compose.yml -f docker-compose.ladder.yml -f docker-compose.prod.yml \
  up -d postgres
bash scripts/restore.sh <archive> skybook-postgres-1
docker compose --env-file .env --env-file deploy/environments/prod.env \
  -f docker-compose.yml -f docker-compose.ladder.yml -f docker-compose.prod.yml \
  up -d
```

Caddy obtains a fresh certificate on first boot; the images arrive from GHCR
by digest. Nothing is rebuilt during a recovery — a disaster is the worst
possible moment to discover a build no longer reproduces.

## 7. The weekly drill (what keeps this document true)

`promote.yml` → `dr-drill`, Mondays 04:00 UTC and on demand: stands up a
stack from the latest images, writes real data through the API, backs up,
**destroys the database volume**, restores, verifies every row count against
the manifest, and finally proves the platform serves the restored data
through the gateway. A failed drill fails the workflow — visibly, before the
real event does it invisibly.

First verified end-to-end on 2026-08-04 against a live stack: six databases,
1,336,748 rows, restored and matched exactly; re-running the corrective
arrival-time script on restored data confirmed as a recorded no-op.
