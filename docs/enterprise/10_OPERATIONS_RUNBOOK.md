# SKB-DOC-10 — Operations Runbook

| | |
|---|---|
| **Document ID** | SKB-DOC-10 |
| **Version** | 1.0 |
| **Status** | Baselined |
| **Owner** | Platform Engineering |
| **Effective date** | 2026-08-01 |
| **Companions** | `docs/DR_RUNBOOK.md` (disaster recovery), `docs/ENVIRONMENTS.md`, `docs/DEPLOY_ORACLE.md` |

## 1. Operational surfaces

| Surface | Where | Access |
|---|---|---|
| Public app | `https://$SKYBOOK_DOMAIN` (Caddy → frontend → gateway) | Internet |
| Grafana (dashboards: fleet, resilience) | VM `127.0.0.1:3001` | `ssh -L 3001:localhost:3001 ubuntu@<VM>` |
| Prometheus | `127.0.0.1:9090` | tunnel |
| Mailpit (if internal mail) | `127.0.0.1:8025` | tunnel |
| Raw gateway | `127.0.0.1:8080` | tunnel; for API-level diagnosis |
| Health | each service `/actuator/health/livez` and `/readyz` | on-host |

First triage commands on the VM:

```bash
docker compose ps                                  # who is unhealthy
docker compose logs --tail 200 <service>           # last errors
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d <service>   # restart one
```

## 2. Scheduled background jobs (know these before diagnosing "ghosts")

| Job | Service | Cadence | Effect |
|---|---|---|---|
| Stale-draft sweep | booking | continuous | Unpaid DRAFTs expire; their seat holds release (TTL-aligned with inventory) |
| FLOWN coupon sweep | booking | hourly | CHECKED_IN coupons on departed flights → FLOWN |
| Fare-alert sweep | booking | hourly | Reprices watches; may send mail |
| No-show sweep | checkin | at gate close | OPEN check-ins → NO_SHOW; passes revoked |
| Manifest finalisation | checkin | post-departure | Freezes the manifest |

A passenger state that "changed by itself" is almost always one of these —
check the job's log line before suspecting a bug.

## 3. Common incidents

### 3.1 A service is unhealthy / restarting
`docker compose logs <svc>`: the two classic causes are a missing/invalid
`.env` secret (fail-fast messages name the variable) and Kafka not yet up
(consumers retry; readiness stays red until connected). Postgres full or
restarted mid-migration: check Flyway output at the top of the log —
`flyway_schema_history` must end in `Success`.

### 3.2 Mail not arriving
Check which SMTP the deployment uses (`MAIL_*` in `.env`): Mailpit means
mail is internal — view via tunnel :8025. Real SMTP: look for notification
ERRORs; Gmail app-password expiry is the usual cause. **A mail failure never
fails bookings** (FR-NOTF-02) — do not restart business services for a mail
problem.

### 3.3 "Payment stuck PENDING" / booking never confirms
Trace the event chain: booking published CREATED? (booking logs) →
payment consumed it? (payment logs; check the DLT if not) → after capture,
PAYMENT_SUCCEEDED consumed by booking? Consumers are idempotent, so
re-delivery is safe; DLT drainage procedure is in `RESILIENCE_MODULE.md`.

### 3.4 Seat looks stuck (held/reserved but nobody owns it)
Holds expire by TTL — wait one sweep cycle first. A reservation without a
live booking indicates a compensation gap: find the booking id in the
reservation row, check its status, release via the admin console. Every such
case is an S2 defect to root-cause, not just clean up.

### 3.5 Refund questioned by a passenger
The system quotes before it executes; evidence chain: booking history rows →
the CANCELLED/PARTIALLY_CANCELLED event (`refundTierPercent`,
`refundBreakdown`) → payment's refund row (amount + withheld fee) → the
mail sent. All four must and do agree; if any disagrees it is an S1.

### 3.6 Boarding-pass QR fails verification
Expected for revoked/reissued passes (seat changed after check-in — only
the newest pass verifies) and after whole-booking cancellation. Only a
*current* pass failing is an incident (check `CHECKIN_BOARDING_PASS_KEY`
consistency — a key change invalidates all passes).

### 3.7 Certificate / site down
Caddy renews Let's Encrypt automatically; failures are almost always port 80
blocked (VM iptables or the cloud security list — both must allow) or the
DuckDNS record pointing at a stale IP. `docker compose logs caddy`.

## 4. Routine maintenance

- **Backups:** per DR runbook — per-database dumps before every promote and
  on schedule; test-restore quarterly.
- **Disk:** builds leave dangling images — `docker system prune -f`
  monthly; check `df -h` (the boot volume also holds Postgres).
- **Idle reclaim (Always Free):** the stack's baseline keeps CPU above
  Oracle's idle threshold; if the fleet is ever slimmed, re-check.
- **Certificate/domain:** DuckDNS IP after any VM recreate; `SKYBOOK_DOMAIN`
  changes require a Caddy restart only.

## 5. Escalation & change discipline

Production changes go through SKB-DOC-09 — including "quick" ones. The only
in-place actions sanctioned for on-call are: service restart, DLT drain,
seat-state cleanup via the admin console, and backup/restore per DR runbook.
Anything else is a change, not an operation.
