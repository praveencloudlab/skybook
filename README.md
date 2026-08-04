# SkyBook

[![CI](https://github.com/praveencloudlab/skybook/actions/workflows/ci.yml/badge.svg)](https://github.com/praveencloudlab/skybook/actions/workflows/ci.yml)
[![Promote](https://github.com/praveencloudlab/skybook/actions/workflows/promote.yml/badge.svg)](https://github.com/praveencloudlab/skybook/actions/workflows/promote.yml)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=praveencloudlab_skybook&metric=alert_status)](https://sonarcloud.io/project/overview?id=praveencloudlab_skybook)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=praveencloudlab_skybook&metric=coverage)](https://sonarcloud.io/project/overview?id=praveencloudlab_skybook)

An airline reservations platform, built end to end as a working demonstration
of production engineering practice: eight Spring Boot microservices behind an
API gateway, PostgreSQL and Kafka underneath, a React web client in front, and
the machinery around it — CI/CD with an eight-environment promotion ladder,
nightly end-to-end certification, load thresholds, observability, and a weekly
disaster-recovery drill that actually restores.

A passenger can search without an account, price a trip against a real fare
calendar, pick a seat from the actual cabin, pay, receive a confirmation
email, check in inside the correct window, and carry a boarding pass with a
QR code the gate-operations console verifies. Two passengers cannot take the
same seat; a cancellation refunds by the fare rules that were actually bought.

**Two book-length case studies** document how it was built — decisions,
defects, reversals and all: [`docs/case-study/`](docs/case-study/) (backend
and frontend, DOCX + PDF, with every screen photographed from the running
platform).

## Run everything locally

Prerequisites: Docker Desktop (Compose v2.24+; verified against Docker 29.5).

```bash
cp env.example .env    # then fill it in - see below
docker compose up --build
```

That is LOCAL, the first rung of the [environment ladder](docs/ENVIRONMENTS.md).
It starts PostgreSQL (six databases, one per service), a single-node Kafka
broker (KRaft), all eight services, the web client, Mailpit as the mail sink,
and the observability suite.

| Front door | Where |
|---|---|
| **The web app** | http://localhost:3000 |
| API gateway | http://localhost:8080 |
| Mail that would have been sent | http://localhost:8025 (Mailpit) |
| Grafana (dashboards, logs, traces) | http://localhost:3001 |

First run takes longer than subsequent ones — Postgres and Kafka do one-time
initialization and Maven's dependency cache is cold. Subsequent
`docker compose up --build` runs reuse the BuildKit cache and the
bind-mounted data directories.

For frontend work with hot reload, run the stack and then:

```bash
cd frontend && npm install && npm run dev
```

Vite serves on http://localhost:5173 and proxies `/api` to the gateway.

### `.env`

Copy `env.example` to `.env` (gitignored). The platform signs sessions with
RS256, so it needs a real keypair — generate one:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem
openssl rsa -in private.pem -pubout -out public.pem
```

then paste each key into `.env` as a single line without the BEGIN/END
markers (`grep -v 'PRIVATE KEY' private.pem | tr -d '\n'`).

| Variable | Required | Notes |
|---|---|---|
| `POSTGRES_PASSWORD` | Yes | One Postgres instance, six databases. |
| `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` | Yes | The RS256 pair above. auth-service signs with the private key; the gateway and every service validate with the public one. |
| `*_SERVICE_CLIENT_SECRET` (booking, checkin, payment, inventory) | Yes | Per-service credentials for service-to-service calls — each service mints its own token; there is no shared god-secret. |
| `CHECKIN_BOARDING_PASS_KEY` | Yes | Signs boarding-pass QR payloads (32+ bytes) so the gate console can verify them. |
| `GRAFANA_ADMIN_PASSWORD` | Yes | Grafana admin login. |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | For real email only | By default mail goes to Mailpit and these are unused. Point `SPRING_MAIL_*` at a real SMTP relay only when you genuinely want mail delivered. |
| `SKYBOOK_BOOTSTRAP_ADMIN_EMAIL` | No | Promote one registered account to ADMIN at auth-service boot — the only path to the admin console; there is no admin-by-API. |

### Checking on it

```bash
docker compose ps                 # every service should show (healthy)
docker compose logs -f <service>  # tail one service's logs
curl http://localhost:8080/actuator/health   # gateway
```

### Sample data

The stack ships with a year of bookable flights (30 named routes plus a full
mesh, daily departures, today → +12 months), a fleet, real seat maps, and
per-flight inventory — enough to search and book end to end out of the box.
See [`docs/SEED_DATA.md`](docs/SEED_DATA.md); re-seed any environment with
`bash scripts/seed/seed.sh [postgres-container]`. Seeding ends by re-expressing
cross-timezone arrivals on the destination's clock, so every fresh database is
born with correct arrival boards.

## Quality — measured, not asserted

Everything below is produced by machinery on every push, and the links are the
proof rather than the claim:

- **[SonarCloud quality gate](https://sonarcloud.io/project/overview?id=praveencloudlab_skybook): passing** — 91% coverage, zero open bugs, zero vulnerabilities, security and reliability at A. The eight CSRF findings were closed with written justifications beside the code, not waved through.
- **1,586 backend tests** (unit + integration via Testcontainers) run in [CI](.github/workflows/ci.yml) on every push, plus 55 frontend Vitest tests.
- **Nightly e2e certification** ([e2e.yml](.github/workflows/e2e.yml)) drives the whole customer journey through the gateway against the composed fleet — including a genuine double-sell race, email into the sink, and a distributed trace asserted across the Kafka hop.
- **Images are scanned before they are pushed** (Trivy, HIGH/CRITICAL gate) and published to GHCR as multi-arch manifests (amd64 for CI, arm64 for the production VM).

## The environment ladder

```
LOCAL → DEV → SIT → TEST/QA → PERF → UAT → STAGING/PRE-PROD → PROD
                                                            ↘ DR (weekly drill)
```

Every rung exists and runs — [`docs/ENVIRONMENTS.md`](docs/ENVIRONMENTS.md) is
the map. One principle carries it: **build once, promote many**. CI builds and
scans every image once per commit; [`promote.yml`](.github/workflows/promote.yml)
then walks those exact digests through ephemeral DEV/SIT/QA/PERF environments
(per-run secrets, per-rung seeding), a human UAT gate with recorded acceptance
evidence, a transient staging rehearsal on the production host, and a
digest-pull production deploy that ends with a backup.

DR is a drill, not a paragraph: weekly, the pipeline backs up a live stack,
**destroys the database volume**, restores, and verifies every row count
against the manifest — [`docs/DR_RUNBOOK.md`](docs/DR_RUNBOOK.md) carries the
RPO/RTO arithmetic and the human procedures.

Production runs on a single Oracle Cloud free-tier VM behind Caddy with
automatic TLS — [`docs/DEPLOY_ORACLE.md`](docs/DEPLOY_ORACLE.md) is the full
path from nothing to a public URL, shape-hunting retry loop included.

## Documentation

The [`docs/`](docs/) tree is written as the project's real engineering record
— frozen design documents per increment, decisions with reasons, and test
reports. Start with:

| Doc | What it covers |
|---|---|
| [`ARCHITECTURE.md`](docs/ARCHITECTURE.md) | The system at a glance |
| [`ENVIRONMENTS.md`](docs/ENVIRONMENTS.md) | The promotion ladder, gate by gate |
| [`DR_RUNBOOK.md`](docs/DR_RUNBOOK.md) | Backup, restore, and the weekly drill |
| [`SECURITY_HARDENING_MODULE.md`](docs/SECURITY_HARDENING_MODULE.md) | RS256, per-service credentials, ownership, the cookie decision |
| [`E2E_CERTIFICATION_MODULE.md`](docs/E2E_CERTIFICATION_MODULE.md) | What the nightly certifies, and what it deliberately does not |
| [`OBSERVABILITY_MODULE.md`](docs/OBSERVABILITY_MODULE.md) | Logs, metrics, traces, and how to chase one request |
| [`case-study/`](docs/case-study/) | The two book-length case studies, DOCX + PDF |

### Observability

Every service logs JSON to stdout (Promtail → Loki), exposes
`/actuator/prometheus` (Prometheus), and runs under the OpenTelemetry Java
agent (traces → Tempo, `traceparent` propagated across HTTP and Kafka hops).

**To find what happened to a specific request:** Grafana → Explore → Loki,
query `{service="api-gateway"}` and filter. Every JSON log line carries a
`trace_id` — click it to jump into the Tempo waterfall for that exact request
with per-hop latency. The "SkyBook Fleet" dashboard has fleet-wide request
rate, error rate, p95 latency, JVM heap, and a live error-log panel.

| What | Where |
|---|---|
| **Grafana** (start here) | http://localhost:3001 — `admin` / `$GRAFANA_ADMIN_PASSWORD` |
| Prometheus | http://localhost:9090 |
| Loki (query via Grafana Explore) | http://localhost:3100 |
| Tempo (query via Grafana Explore) | http://localhost:3200 |

### Resetting

`docker compose down` stops everything but **keeps your data**
(Postgres/Kafka state lives in `./docker-data/`, bind-mounted). For a
genuinely clean slate:

```bash
docker compose down
rm -rf ./docker-data
docker compose up --build
```

## Troubleshooting

**A service is stuck `Exited (1)` right after startup, with a Postgres "connection refused" in its logs, only on the *very first* `docker compose up --build` against an empty `./docker-data/`.**
This is a one-time Postgres quirk, not a bug in the app: on a truly first run, the official `postgres` image starts a *temporary* bootstrap server to run the init scripts (creating the six `skybook_*` databases), shuts it down, then starts the real server — a restart cycle that can take several seconds. Every DB-owning service is configured with `SPRING_DATASOURCE_HIKARI_INITIALIZATION_FAIL_TIMEOUT=-1` specifically so a service that starts mid-restart retries the connection instead of crashing — if you still see this, just re-run `docker compose up`; the data Postgres already initialized is on disk in `./docker-data/postgres`, so it won't repeat the temp-server dance on the next start.

**`docker compose build` is slow / re-downloads dependencies every time.**
Needs BuildKit (on by default in current Docker Desktop). If it's somehow disabled, `export DOCKER_BUILDKIT=1` before building — the Maven dependency cache mount (`--mount=type=cache,target=/root/.m2`) only works under BuildKit.

**A service can't reach another service (e.g. `booking-service` failing to call `flight-service`).**
Check the `*_BASE_URL` environment variables in `docker-compose.yml` — they must use the other service's Compose *service name* as the hostname (e.g. `http://flight-service:8082`), not `localhost`. `localhost` inside a container refers to that container itself, not its neighbors.

**Windows/Git Bash specifically: a bind mount silently ends up empty, or `docker run`/`docker exec` fails with a mangled path like `C:/Program Files/Git/...`.**
Git Bash auto-converts anything that looks like a POSIX path in command arguments, including inside `-v host:container` mount specs and container-internal paths passed to `docker exec`. Prefix the command with `MSYS_NO_PATHCONV=1` when running `docker` directly from Git Bash. `docker compose` itself isn't affected (paths in `docker-compose.yml` aren't shell arguments), only ad hoc `docker run`/`docker exec` invocations are.

**`tempo` fails to start with `not a directory ... Are you trying to mount a directory onto a file` pointing at the *host* path.**
Misleading error — the problem is the *container-side* path, not the host one: in the `grafana/tempo` image, `/tempo` is the Tempo **binary** (its entrypoint), so mounting a data directory there collides with a file that already exists in the image. The compose file mounts data at `/var/tempo` for exactly this reason; if you change it, don't change it back to `/tempo`.

**Traces aren't appearing in Tempo/Grafana.**
Check any app service's first log lines for the `otel.javaagent` banner (agent attached) and for exporter warnings. The agent defaults to `http/protobuf` on port 4318; this stack explicitly sets `OTEL_EXPORTER_OTLP_PROTOCOL=grpc` to match Tempo's `4317` receiver — if you see a "port is likely incorrect for protocol" warning, those two env vars have drifted apart.
