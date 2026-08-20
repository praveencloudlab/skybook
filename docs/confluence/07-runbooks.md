# Runbooks

This page is the operational how-to collection for SkyBook: step-by-step procedures, with exact commands, for the operations we actually perform on the platform — restarting services, verifying mail, reseeding data, backup/restore, Kafka replays, unblocking users, goodwill refunds, and reading telemetry. It is written for anyone operating a local compose stack, an environment-ladder stack in CI, or the production stack on the Oracle Cloud VM. Release promotion itself is covered in `04-cicd-release-process.md` and `08-deployment-procedures.md`; environment layout is in `05-environment-guide.md`. The deeper incident catalogue lives in the repo at `docs/enterprise/10_OPERATIONS_RUNBOOK.md`, and disaster-recovery policy in `docs/DR_RUNBOOK.md`.

## Quick reference — operational surfaces (local compose)

| Surface | URL / port | Notes |
|---|---|---|
| Frontend SPA | http://localhost:3000 | nginx serves the bundle, proxies `/api` to the gateway |
| API gateway | http://localhost:8080 | The only business port published to the host |
| Mailpit (mail sink) | http://localhost:8025 | Web UI + HTTP API; SMTP on internal port 1025 |
| Grafana | http://localhost:3001 | `admin` / `$GRAFANA_ADMIN_PASSWORD`; 3001 because the frontend owns 3000 |
| Prometheus | http://localhost:9090 | Metrics |
| Loki | http://localhost:3100 | Logs (query via Grafana Explore) |
| Tempo | http://localhost:3200 | Traces (query via Grafana Explore) |
| Postgres, Kafka, services | internal only | Not host-published; reach via `docker exec` |

Container names below assume the default compose project (`skybook-postgres-1`, `skybook-kafka-1`). The CI environment ladder prefixes the project name: `skybook-dev-postgres-1`, `skybook-qa-postgres-1`, and so on. On the production VM, observability and Mailpit bind to `127.0.0.1` only — reach them with an SSH tunnel, e.g. `ssh -L 3001:localhost:3001 <user>@<vm>`.

---

## RB-1: Restart or redeploy one service locally

Rebuild and roll a single service without touching the rest of the stack:

```bash
docker compose build booking-service
docker compose up -d booking-service
docker compose ps                                # wait for (healthy)
docker compose logs --tail 200 booking-service   # confirm clean start
```

Valid service names: `auth-service`, `flight-service`, `booking-service`, `inventory-service`, `payment-service`, `checkin-service`, `notification-service`, `api-gateway`, `frontend`.

**Stale-image gotcha (check this FIRST when behavior looks wrong).** Local compose happily runs an old locally-built image; symptoms that look like data bugs or missing features (wrong flight durations, absent SSO button) have repeatedly been image skew, not code. Always compare image build time against the last relevant commit:

```bash
docker images --format 'table {{.Repository}}\t{{.Tag}}\t{{.CreatedAt}}' | grep skybook
git log -1 --format='%h %ad %s' -- backend/booking-service backend/skybook-common
```

If the image predates the newest commit touching the service **or `backend/skybook-common`**, rebuild. `skybook-common` is baked into the flight, booking, checkin, and notification images — a change there requires rebuilding all four, or they keep serving the old shared code. Rebuild from a clean worktree if you have uncommitted WIP you don't intend to ship.

On the production VM, services run pulled GHCR images, never local builds. Restart-in-place is:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d <service>
```

Shipping new code to prod is a release, not a restart — it goes through the promote ladder (`04-cicd-release-process.md`).

---

## RB-2: Check email delivery

All non-production environments deliver mail to Mailpit, never to a real mailbox.

1. **Web UI:** open http://localhost:8025 and eyeball the inbox.
2. **API — search by recipient** (this is exactly what the CI smoke and UAT scripts do):

```bash
curl -s "http://localhost:8025/api/v1/search?query=to:passenger@example.com"
```

The JSON response includes a `total` count and message summaries. To pull a registration OTP out of the sink the way `.github/scripts/smoke.sh` does:

```bash
curl -sf "http://localhost:8025/api/v1/search?query=to:$WHO" \
  | grep -oE 'verification code: [0-9]{6}' | grep -oE '[0-9]{6}' | head -1
```

**If no mail arrives:** verify notification-service is pointed at Mailpit. The compose default is `SPRING_MAIL_HOST=mailpit` / `SPRING_MAIL_PORT=1025`; without that override the service falls back to its `application.yml` default of real Gmail with placeholder credentials and **drops every email silently**.

```bash
docker inspect skybook-notification-service-1 \
  --format '{{range .Config.Env}}{{println .}}{{end}}' | grep SPRING_MAIL
```

A mail failure never fails bookings by design — do not restart business services for a mail problem; fix the mail configuration and replay if needed (RB-5).

On the VM: prod Mailpit is bound to `127.0.0.1:8025`; the transient staging stack's Mailpit sits at `127.0.0.1:8125` on the same host (staging cannot own 8025 while prod does).

---

## RB-3: Reseed reference data

All seeders run `psql` inside the Postgres container; the scripts export `MSYS_NO_PATHCONV=1` themselves, so Git Bash on Windows is fine. Run from the repo root with the stack up.

| Script | Scope | Safety |
|---|---|---|
| `scripts/seed/seed.sh [container]` | Full reseed: a year of flights, fleet + seat maps, inventory, mesh densification, terminals, modern fleet + re-fleet, then India and UK networks | **Replaces** the flights and inventory tables — use on fresh/disposable stacks |
| `scripts/seed/seed_india.sh [container]` | India domestic network (fleet, 540 daily departures, per-flight hulls) | Additive + idempotent — safe on a live stack with bookings, safe to re-run |
| `scripts/seed/seed_uk.sh [container]` | UK domestic + Crown Dependency network (CAA-demand-derived frequencies) | Additive + idempotent — same guarantees |
| `scripts/seed/refleet.sh [container]` | Reassign untouched flight inventories to mission-appropriate aircraft | Safe to re-run; flights with sold seats keep their metal; India/UK flights keep their own hulls |

```bash
bash scripts/seed/seed.sh                       # local default container skybook-postgres-1
bash scripts/seed/seed.sh skybook-dev-postgres-1   # a ladder environment's Postgres
bash scripts/seed/seed_india.sh                 # additive, live-stack safe
bash scripts/seed/seed_uk.sh
```

The India/UK SQL files are generated artifacts — regenerate from the airport/route tables with `node scripts/seed/gen_india_network.mjs` or `node scripts/seed/gen_uk_network.mjs`, don't hand-edit.

**Arrival-time contract:** every seeder authors arrival times destination-local. Never chain the standalone `scripts/fix-arrival-times-to-destination-local.sql` after an additive seed — it would shift already-correct rows; it exists (marker-guarded) only for databases seeded before this contract.

---

## RB-4: Database backup and restore

Backups are logical per-database dumps of all six databases (`skybook_auth`, `skybook_flight`, `skybook_booking`, `skybook_inventory`, `skybook_payment`, `skybook_checkin`) plus a MANIFEST of exact per-database row counts, so a restore is verified against what was backed up, not merely declared done.

**Backup:**

```bash
bash scripts/backup.sh [output-dir] [container] [retention-days]
bash scripts/backup.sh              # defaults: ./backups, skybook-postgres-1, 14 days retention
```

Produces `backups/skybook-<UTC-timestamp>.tar.gz` (pg_dump custom format, compressed) and prunes archives older than the retention window. Nightly cadence on the VM sets the RPO at up to 24 hours (a stated decision in `docs/DR_RUNBOOK.md`). The prod job in `promote.yml` also takes a post-deploy backup so the restore point brackets each release.

**Restore:**

```bash
bash scripts/restore.sh <archive.tar.gz> <container>
```

The target container is deliberately mandatory — no default-to-production footgun. It restores each dump with `pg_restore --clean --if-exists --no-owner`, then re-counts every database and compares against the MANIFEST. Exit 0 means row counts matched; any mismatch exits 1.

**The weekly DR drill (mirror this if restoring for real).** `promote.yml` runs the `dr-drill` job every Monday 04:00 UTC (`cron: '0 4 * * 1'`): stand up a stack, seed it, write real data via the smoke, back it up, **destroy the database entirely**, restore, and prove the platform reads the restored data. The manual equivalent:

```bash
bash scripts/backup.sh ./backups skybook-postgres-1 1
docker compose stop postgres && docker compose rm -f postgres
# remove the postgres data (volume or ./docker-data/postgres bind mount, per your stack)
docker compose up -d postgres          # wait for pg_isready
bash scripts/restore.sh ./backups/skybook-*.tar.gz skybook-postgres-1
docker compose restart auth-service flight-service booking-service \
  inventory-service payment-service checkin-service notification-service
```

Then run a real user journey (or `.github/scripts/smoke.sh http://localhost:8080 http://localhost:3000`) to confirm the platform serves the restored data.

---

## RB-5: Re-send ticket emails via Kafka replay

Use case: a passenger needs their confirmation/ticket email again (mailbox lost it, or templates changed after issue). Notification-service attaches the ticket document when it consumes a `CONFIRMED` event from `skybook.booking.events` — so replaying the booking's original CONFIRMED event re-sends the ticket without touching any business state.

1. **Capture the topic** (Kafka tooling lives in the broker container at `/opt/kafka/bin`):

```bash
docker exec skybook-kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic skybook.booking.events \
  --from-beginning --timeout-ms 15000 > booking-events.jsonl
```

2. **Isolate exactly one event** — the CONFIRMED event for the booking in question (the JSON carries `"type":"CONFIRMED"` and the booking reference):

```bash
grep '"type":"CONFIRMED"' booking-events.jsonl | grep '<PNR>' > replay.json
wc -l replay.json     # must be exactly 1
```

3. **Re-produce it onto the same topic:**

```bash
docker exec -i skybook-kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic skybook.booking.events < replay.json
```

4. **Verify** the mail landed: `curl -s "http://localhost:8025/api/v1/search?query=to:<passenger-email>"` (RB-2).

**Why this is safe** — every consumer of the topic was designed for redelivery:

| Consumer group | Reaction to a replayed CONFIRMED |
|---|---|
| `notification-service` | Sends the confirmation email with the ticket attached — the intended effect |
| `checkin-service` | Creates check-ins idempotently by `bookingPassengerId` — a redelivered event is a no-op |
| `payment-service` | Only acts on `CREATED`; everything else is logged and ignored |
| `booking-service` | Consumes payment events, not this topic |

**Warning:** replay only `CONFIRMED` events. `CANCELLED` / `PARTIALLY_CANCELLED` events drive refund handling in payment-service — never re-produce those. If a consumer group looks stuck instead, check offsets first: `docker exec skybook-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group notification-service`.

---

## RB-6: Unblock a user stuck on OTP verification

A new account cannot sign in until it redeems the 6-digit code mailed at registration. Defaults (config keys `app.email-verification.*` in auth-service): code TTL **10 minutes**, **5** failed attempts per code, **60-second** resend cooldown. Only the SHA-256 hash of the code is stored; the code itself exists only in the email.

1. **Self-serve resend** (public endpoint — the caller can't sign in precisely because they aren't verified):

```bash
curl -i -X POST http://localhost:8080/api/auth/resend-verification \
  -H 'Content-Type: application/json' \
  -d '{"email":"stuck.user@example.com"}'
```

Always returns `202 Accepted` whether or not the address exists (anti-enumeration). Inside the 60-second cooldown the outstanding code stands and nothing is re-sent — the 202 is identical, but auth-service logs "still inside cooldown". Past the attempt cap, only a fresh code works; the resend issues one.

2. **Confirm the mail arrived** via Mailpit (RB-2). If not, it's a delivery problem, not an OTP problem.

3. **Inspect the account's verification state** directly:

```bash
docker exec skybook-postgres-1 psql -U postgres -d skybook_auth -c \
  "SELECT u.id, u.email, u.email_verified, o.attempts, o.expires_at, o.last_sent_at
     FROM users u LEFT JOIN email_verification_otps o ON o.user_id = u.id
    WHERE u.email = 'stuck.user@example.com';"
```

One live code per user (`user_id` is UNIQUE) — a resend replaces the row, so the newest email is the only redeemable one.

4. **If the table itself is missing**, verify the V9 migration applied:

```bash
docker exec skybook-postgres-1 psql -U postgres -d skybook_auth -c \
  "SELECT version, description, success FROM flyway_schema_history WHERE version = '9';"
```

`V9__email_verification.sql` adds `users.email_verified` and the `email_verification_otps` table, and grandfathers pre-existing accounts as verified. If V9 shows `success = f`, the migration failed mid-flight — treat as an incident, not a data fix.

---

## RB-7: Goodwill refund via admin

Use case: waive a cancellation fee the fare rules withheld (e.g. refund the withheld portion of a Saver cancellation) as a customer-service gesture.

`PATCH /api/payments/{id}/refund` is **ADMIN-only** (payment-service `SecurityConfig`; the same rule covers manual create, cancel, and the raw refund listing). The bootstrap admin account is designated by `SKYBOOK_BOOTSTRAP_ADMIN_EMAIL` in the environment.

1. **Sign in as an ADMIN** and capture the token:

```bash
TOKEN=$(curl -sf -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"<admin-email>","password":"<admin-password>"}')
```

2. **Find the payment id** from the booking:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/payments/booking/<bookingId>
# or by payment reference:
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/payments/reference/<reference>
```

3. **Issue the refund:**

```bash
curl -s -X PATCH -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  http://localhost:8080/api/payments/<id>/refund \
  -d '{"reason":"Goodwill - fee waived"}'
```

Request body semantics (all fields optional; an **empty body refunds the full remaining captured amount** per the payment's stored fare breakdown):

| Field | Meaning | Constraints |
|---|---|---|
| `fareLines` | Refund only these lines (partial refund) | Omit for full refund |
| `refundPercent` | Time-tier multiplier applied after fare rules | 1–100; null = 100 |
| `premiumPercent` | Separate tier for PREMIUM lines | 1–100; null = `refundPercent` applies to all lines |
| `reason` | Free-text audit reason | ≤ 500 chars |
| `sourceReference` | Idempotency cause, unique per payment | ≤ 120 chars; null (desk refunds) = no dedupe |

Cumulative refunds can never exceed the captured amount — the validator rejects over-refunds, so re-running a goodwill refund cannot double-pay. Verify afterwards with `GET /api/payments/{id}/history` (owner-or-admin) and confirm the refund email in Mailpit.

---

## RB-8: Reading logs, metrics, and traces

Every service runs the pinned OpenTelemetry Java agent (2.14.0) and exports traces over OTLP gRPC to `tempo:4317`; Promtail discovers containers via the Docker socket and ships their logs to Loki; Prometheus scrapes metrics. Grafana fronts all three.

| Tool | Local URL | What it holds |
|---|---|---|
| Grafana | http://localhost:3001 | Dashboards + Explore over Loki and Tempo; login `admin` / `$GRAFANA_ADMIN_PASSWORD` |
| Prometheus | http://localhost:9090 | Metrics |
| Loki | http://localhost:3100 | All container logs (query in Grafana → Explore → Loki) |
| Tempo | http://localhost:3200 | Distributed traces (query in Grafana → Explore → Tempo) |

First-line triage doesn't need Grafana at all:

```bash
docker compose ps                            # who is unhealthy
docker compose logs --tail 200 <service>     # last errors from one service
```

For cross-service investigation, start from the trace: find the failing request in Grafana Explore (Tempo), which stitches the gateway and every downstream hop into one view, then pivot to the same time window in Loki for the services involved.

On the production VM all of these are bound to `127.0.0.1` — tunnel in with `ssh -L 3001:localhost:3001 <user>@<vm>` (same pattern for 9090/3100/3200). Grafana is on 3001, not its default 3000, because the frontend owns 3000.
