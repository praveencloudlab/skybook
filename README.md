# SkyBook

[![CI](https://github.com/praveencloudlab/skybook/actions/workflows/ci.yml/badge.svg)](https://github.com/praveencloudlab/skybook/actions/workflows/ci.yml)
[![Promote](https://github.com/praveencloudlab/skybook/actions/workflows/promote.yml/badge.svg)](https://github.com/praveencloudlab/skybook/actions/workflows/promote.yml)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=praveencloudlab_skybook&metric=alert_status)](https://sonarcloud.io/project/overview?id=praveencloudlab_skybook)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=praveencloudlab_skybook&metric=coverage)](https://sonarcloud.io/project/overview?id=praveencloudlab_skybook)

A portfolio project by **Praveenreddy Somireddy** — an airline reservations
platform, built end to end as a working demonstration of production
engineering practice: eight Spring Boot applications (one API gateway and
seven domain services), PostgreSQL and Kafka underneath, a React web client in
front, and the machinery around it — CI/CD with an eight-environment promotion
ladder, nightly end-to-end certification, load thresholds, observability, and a
weekly disaster-recovery drill that actually restores.

A passenger can search without an account, price a trip against a real fare
calendar, pick a seat from the actual cabin, pay, receive a confirmation
email, check in inside the correct window, and carry a boarding pass with a
QR code the gate-operations console verifies. Two passengers cannot take the
same seat; a cancellation refunds by the fare rules that were actually bought.

**Two book-length case studies** document how it was built — decisions,
defects, reversals and all: [`docs/case-study/`](docs/case-study/) (backend
and frontend, DOCX + PDF, with every screen photographed from the running
platform).

---

## Contents

- [What it does](#what-it-does)
- [Architecture](#architecture)
- [The services](#the-services)
- [How the hard parts work](#how-the-hard-parts-work)
- [Run everything locally](#run-everything-locally)
- [Configuration (`.env`)](#env)
- [Sample data](#sample-data)
- [Quality — measured, not asserted](#quality--measured-not-asserted)
- [The environment ladder](#the-environment-ladder)
- [Project layout](#project-layout)
- [Documentation](#documentation)
- [Observability](#observability)
- [Troubleshooting](#troubleshooting)

---

## What it does

### For a passenger

| Capability | Notes |
|---|---|
| **Search without an account** | Public shopping data — browse and price before signing up, the way every travel site works. Login is required only at booking. |
| **One-way, round trip, multi-city** | A round trip is a **single PNR** with two segments, one payment and one confirmation — not two bookings stapled together. |
| **Fare families** | SAVER / FLEXI / PREMIUM per passenger, each with its own change and refund rules. Premium is fully refundable until 6 h before departure. |
| **Fare calendar** | Per-date lowest fares from the same pricing formula checkout uses, so the calendar can never disagree with the price you pay. |
| **Seat selection** | Real cabin maps. Free seats auto-assign; preferred seats carry a surcharge. Two passengers cannot hold the same seat — enforced under a pessimistic lock, not hope. |
| **Payment** | Authorize → capture against a simulated gateway, with invoices and refunds. |
| **Confirmation email** | HTML with a QR code and a PDF e-ticket attached. |
| **Online check-in** | Opens 24 h before departure, closes 45 min before, judged on the **departure airport's** clock. |
| **Boarding pass** | On screen, downloadable, and emailable — QR verified by the gate console. |
| **Guest check-in** | Booked by an agency and have no account? Retrieve with booking reference + surname, check in, and get your pass. No login. |
| **Sign in with Google** | OIDC, with the external identity exchanged for a SkyBook token at the boundary. |
| **Manage a booking** | Change flight/date/bags, cancel whole or per passenger, see exactly what a cancellation refunds *before* confirming. |
| **Profile** | Saved travellers, passport details, language and currency preferences that follow the account. |

### For staff (admin console)

Flight scheduling, fleet and seat-map management, inventory, gate operations
(boarding-pass verification, gate assignment, manifest finalisation), and
booking search. Admin is granted by promoting a registered account at
auth-service boot — there is deliberately no admin-by-API.

---

## Architecture

```
                    ┌──────────────┐
   browser ────────►│   frontend   │  React 19 + Vite + Tailwind (nginx)
                    └──────┬───────┘
                           │  /api/*  (same origin — no CORS in the normal path)
                    ┌──────▼───────┐
                    │ api-gateway  │  routing · JWT enforcement · rate limiting
                    └──────┬───────┘  the ONLY place a browser credential
                           │          becomes a downstream Bearer token
      ┌───────────┬────────┼────────┬───────────┬──────────────┐
      ▼           ▼        ▼        ▼           ▼              ▼
 ┌─────────┐ ┌────────┐ ┌───────┐ ┌─────────┐ ┌────────┐ ┌────────────┐
 │  auth   │ │ flight │ │booking│ │inventory│ │payment │ │  checkin   │
 └────┬────┘ └───┬────┘ └───┬───┘ └────┬────┘ └───┬────┘ └─────┬──────┘
      │          │          │          │          │            │
      └──────────┴──────────┴────┬─────┴──────────┴────────────┘
                                 │
                 ┌───────────────┼────────────────┐
                 ▼               ▼                ▼
          ┌────────────┐  ┌────────────┐  ┌──────────────┐
          │ PostgreSQL │  │   Kafka    │  │ notification │
          │ 6 databases│  │  (KRaft)   │  │   (email)    │
          └────────────┘  └────────────┘  └──────────────┘
```

**Principles the code actually holds to:**

- **The gateway is the only translation point** between a browser credential
  (httpOnly cookie) and downstream bearer authentication. Services never trust
  a header for identity — each re-validates the RS256 token itself.
- **One database per service**, on one PostgreSQL instance. No service reads
  another's tables; they talk over HTTP (Feign) or Kafka.
- **Asymmetric signing.** auth-service alone holds the private key. Every other
  service verifies with the public key. There is no shared god-secret — each
  service has its own credential and mints its own scoped service token.
- **Events for facts, HTTP for questions.** A booking asks flight-service
  whether a flight exists; it *announces* that a booking was confirmed.

---

## The services

| Service | Port | Owns | Talks to |
|---|---|---|---|
| **api-gateway** | 8080 | Routing table, JWT enforcement, per-source rate limiting, credential translation | everything |
| **auth-service** | 8081 | Accounts, RS256 token issuance, Google SSO, guest sessions, profile, saved travellers | Kafka (email events) |
| **flight-service** | 8082 | Flights, schedules, routes, airports, fare calendar | — |
| **booking-service** | 8083 | Bookings, PNRs, passengers, segments, tickets/coupons, cancellation policy, the booking saga | flight, inventory, Kafka |
| **inventory-service** | 8084 | Seat maps, seat holds and reservations, per-flight inventory | flight |
| **notification-service** | 8085 | Email rendering (HTML + QR), PDF e-tickets and boarding passes | Kafka (consumer only) |
| **payment-service** | 8086 | Payments, authorize/capture, refunds by fare rules, invoices | Kafka |
| **checkin-service** | 8087 | Check-in windows, boarding passes, baggage, flight manifests | flight, inventory, Kafka |

Two shared libraries, deliberately thin: **skybook-common** (events, constants,
error shape, airport timezones) and **skybook-security** (the single JWT
validator and ownership rules every service uses, so the edge and the services
can never drift apart).

---

## How the hard parts work

**Booking is a saga, not a transaction.** Creating a booking spans three
services and cannot be one database transaction. It runs as: create a DRAFT →
hold a seat per passenger in inventory → finalise. Every stage commits before
the next begins, and a failure at any point compensates the previous ones
(releasing holds, cancelling the draft). Unfinished drafts are swept after 15
minutes.

**Seat exclusivity is enforced where the seats live.** Two passengers racing
for 12A are serialised by a pessimistic lock on the flight's inventory row;
the loser gets a 409, not a double-sold seat. A passenger's *existing* hold
replays rather than erroring, so a retry is free.

**Money is quoted once and honoured.** The refund a passenger is shown before
cancelling is computed by booking-service and carried *on the cancellation
event* to payment-service, which pays exactly that. Two services deriving a
time-sensitive percentage from their own clocks would drift apart in the
seconds between quoting and capturing.

**Time is airport-local, always.** A flight departs on its origin's clock and
arrives on its destination's. Durations come from the server as minutes; the
frontend formats them and never subtracts two wall clocks. This sounds
obvious and was a real bug — see the case study.

**Check-in cannot be gamed by a clock.** Windows are evaluated against the
departure airport's timezone, in the service, on every call. The UI shows the
same rule but is never the thing that enforces it.

---

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

### Running the tests

```bash
cd backend && mvn test                    # unit + slice tests, all modules
cd backend && mvn verify -pl e2e-tests -Pe2e   # end-to-end, needs the stack up
cd frontend && npm test -- --run          # Vitest
```

The e2e suite drives the real customer journey through the gateway against
the composed fleet — it needs `docker compose up` first, and it manipulates
containers (the service-down scenario), so it is the one suite that is not
hermetic.

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
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | No | "Sign in with Google". **Empty means the feature is off** — no OAuth beans, no button, and every environment behaves exactly as before it existed. See [`SSO_MODULE.md`](docs/SSO_MODULE.md) §6.4 for the one-time console setup. |

### Checking on it

```bash
docker compose ps                 # every service should show (healthy)
docker compose logs -f <service>  # tail one service's logs
curl http://localhost:8080/actuator/health   # gateway
```

### Sample data

The stack ships with a year of bookable flights (120+ curated route entries
across 29 airports — UK, Europe, Gulf, six Indian internationals, seven US
gateways, Africa, Asia, Australia — plus a full mesh, daily departures, today
→ +12 months), a fleet, real seat maps, and per-flight inventory — enough to
search and book end to end out of the box.

See [`docs/SEED_DATA.md`](docs/SEED_DATA.md); re-seed any environment with
`bash scripts/seed/seed.sh [postgres-container]`. Seeding ends by
re-expressing cross-timezone arrivals on the destination's clock, so every
fresh database is born with correct arrival boards.

### Resetting

`docker compose down` stops everything but **keeps your data**
(Postgres/Kafka state lives in `./docker-data/`, bind-mounted). For a
genuinely clean slate:

```bash
docker compose down
rm -rf ./docker-data
docker compose up --build
```

---

## Quality — measured, not asserted

Everything below is produced by machinery on every push, and the links are the
proof rather than the claim:

- **[SonarCloud quality gate](https://sonarcloud.io/project/overview?id=praveencloudlab_skybook): passing** — 91% coverage, zero open bugs, zero vulnerabilities, security and reliability at A. The eight CSRF findings were closed with written justifications beside the code, not waved through.
- **1,817 backend test executions** across ten Maven modules (unit, slice, and Testcontainers integration), plus **76 frontend Vitest tests**, run in [CI](.github/workflows/ci.yml) on every push. Measured by `mvn test` at the reactor root on 7 August 2026: 1,817 run, 0 failures, 0 errors, 1 skipped.
- **51 end-to-end tests** across 10 suites ([e2e.yml](.github/workflows/e2e.yml)) drive the whole customer journey through the gateway against the composed fleet — including a genuine double-sell race, email into the sink, a service killed mid-journey, guest check-in, and a distributed trace asserted across the Kafka hop.
- **Images are scanned before they are pushed** (Trivy, HIGH/CRITICAL gate) and published to GHCR as multi-arch manifests (amd64 for CI, arm64 for the production VM).

**On the numbers:** they are re-measured, never typed from memory. A previous
revision of this file quoted a test total that turned out to be a bad `grep`
(it dropped the `[WARNING]` summary lines); the case study now records both
the corrected figure and how it was obtained.

---

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

| Rung | Runs on | Gate |
|---|---|---|
| **LOCAL** | your laptop | you |
| **DEV** | ephemeral, CI runner | health + smoke |
| **SIT** | ephemeral, CI runner | cross-service probes (gateway routing, service credentials, an event through Kafka into the mail sink) |
| **TEST/QA** | ephemeral, CI runner | the full e2e certification suite, against the promoted images |
| **PERF** | ephemeral, CI runner | k6 thresholds — a breach stops the promotion |
| **UAT** | evidence + human | a person approves against a recorded acceptance transcript |
| **STAGING** | the prod VM, transient | smoke on the real host and architecture, then torn down win or lose |
| **PROD** | Oracle VM behind Caddy | health through the TLS front door, then an automatic backup |
| **DR** | weekly drill | row-count verification against the backup manifest |

DR is a drill, not a paragraph: weekly, the pipeline backs up a live stack,
**destroys the database volume**, restores, and verifies every row count
against the manifest — [`docs/DR_RUNBOOK.md`](docs/DR_RUNBOOK.md) carries the
RPO/RTO arithmetic and the human procedures.

Production runs on a single Oracle Cloud VM behind Caddy with automatic TLS —
[`docs/DEPLOY_ORACLE.md`](docs/DEPLOY_ORACLE.md) is the full path from nothing
to a public URL, shape-hunting retry loop included. A complete Kubernetes
manifest tree also exists on a branch, pending cluster verification.

---

## Project layout

```
skybook/
├── backend/                    Maven multi-module reactor
│   ├── api-gateway/            routing, JWT enforcement, rate limiting
│   ├── auth-service/           accounts, tokens, SSO, guest sessions
│   ├── flight-service/         flights, schedules, fare calendar
│   ├── booking-service/        bookings, PNRs, tickets, the saga
│   ├── inventory-service/      seat maps, holds, reservations
│   ├── payment-service/        payments, refunds, invoices
│   ├── checkin-service/        check-in, boarding passes, manifests
│   ├── notification-service/   email, PDF, QR
│   ├── skybook-common/         events, constants, error shape, timezones
│   ├── skybook-security/       the shared JWT validator + ownership rules
│   └── e2e-tests/              the certification suite
├── frontend/                   React 19 + Vite + TypeScript + Tailwind 4
├── docs/                       28 documents - designs, runbooks, case studies
│   └── case-study/             two book-length studies (DOCX + PDF) + 75 screenshots
├── deploy/environments/        one env file per ladder rung
├── scripts/                    seeding, backup, restore
├── perf/k6/                    load scenarios and thresholds
├── k8s/                        Kubernetes manifests
├── .github/workflows/          ci · frontend · promote · e2e
└── docker-compose*.yml         base + ladder/staging/prod/perf/e2e overlays
```

---

## Documentation

The [`docs/`](docs/) tree is written as the project's real engineering record
— frozen design documents per increment, decisions with reasons, and test
reports.

**The governed documentation set** lives in
[`docs/enterprise/`](docs/enterprise/00_DOCUMENT_INDEX.md) — twelve
change-controlled documents (SRS with numbered requirements, HLD, interface
control, data & security architecture, engineering handbook, QA plan,
release policy, operations runbook, and a full requirements-traceability
matrix). [`SKB-DOC-00`](docs/enterprise/00_DOCUMENT_INDEX.md) is the entry
point and defines precedence over everything below. Start with:

| Doc | What it covers |
|---|---|
| [`enterprise/00_DOCUMENT_INDEX.md`](docs/enterprise/00_DOCUMENT_INDEX.md) | The controlled document set — start here |
| [`ARCHITECTURE.md`](docs/ARCHITECTURE.md) | The system at a glance |
| [`ENVIRONMENTS.md`](docs/ENVIRONMENTS.md) | The promotion ladder, gate by gate |
| [`DR_RUNBOOK.md`](docs/DR_RUNBOOK.md) | Backup, restore, and the weekly drill |
| [`SECURITY_HARDENING_MODULE.md`](docs/SECURITY_HARDENING_MODULE.md) | RS256, per-service credentials, ownership, the cookie decision |
| [`SSO_MODULE.md`](docs/SSO_MODULE.md) | Google OIDC, and why the token is exchanged at the boundary |
| [`GUEST_CHECKIN_MODULE.md`](docs/GUEST_CHECKIN_MODULE.md) | No-account check-in, and the 30 review findings that reshaped it |
| [`IDEMPOTENCY_MODULE.md`](docs/IDEMPOTENCY_MODULE.md) | Safe retries on money-adjacent writes |
| [`E2E_CERTIFICATION_MODULE.md`](docs/E2E_CERTIFICATION_MODULE.md) | What the nightly certifies, and what it deliberately does not |
| [`OBSERVABILITY_MODULE.md`](docs/OBSERVABILITY_MODULE.md) | Logs, metrics, traces, and how to chase one request |
| [`case-study/`](docs/case-study/) | The two book-length case studies, DOCX + PDF |

---

## Observability

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

Actuator lives on a separate internal-only management port per service and is
never published to the host — the probe paths `/livez` and `/readyz` are
re-exposed on the main port for Kubernetes.

---

## Troubleshooting

**A service is stuck `Exited (1)` right after startup, with a Postgres "connection refused" in its logs, only on the *very first* `docker compose up --build` against an empty `./docker-data/`.**
This is a one-time Postgres quirk, not a bug in the app: on a truly first run, the official `postgres` image starts a *temporary* bootstrap server to run the init scripts (creating the six `skybook_*` databases), shuts it down, then starts the real server — a restart cycle that can take several seconds. Every DB-owning service is configured with `SPRING_DATASOURCE_HIKARI_INITIALIZATION_FAIL_TIMEOUT=-1` specifically so a service that starts mid-restart retries the connection instead of crashing — if you still see this, just re-run `docker compose up`; the data Postgres already initialized is on disk in `./docker-data/postgres`, so it won't repeat the temp-server dance on the next start.

**`docker compose build` is slow / re-downloads dependencies every time.**
Needs BuildKit (on by default in current Docker Desktop). If it's somehow disabled, `export DOCKER_BUILDKIT=1` before building — the Maven dependency cache mount (`--mount=type=cache,target=/root/.m2`) only works under BuildKit.

**A service can't reach another service (e.g. `booking-service` failing to call `flight-service`).**
Check the `*_BASE_URL` environment variables in `docker-compose.yml` — they must use the other service's Compose *service name* as the hostname (e.g. `http://flight-service:8082`), not `localhost`. `localhost` inside a container refers to that container itself, not its neighbors.

**A backend change doesn't appear to take effect, and you get a `NoSuchMethodError` on something you just added.**
The shared libraries are resolved from your local Maven repository. After changing `skybook-common` or `skybook-security`, install them before testing a dependent module: `mvn -pl skybook-common,skybook-security install -DskipTests`. Building with `-am` does this for you; building a single module with `-pl` alone does not.

**Windows/Git Bash specifically: a bind mount silently ends up empty, or `docker run`/`docker exec` fails with a mangled path like `C:/Program Files/Git/...`.**
Git Bash auto-converts anything that looks like a POSIX path in command arguments, including inside `-v host:container` mount specs and container-internal paths passed to `docker exec`. Prefix the command with `MSYS_NO_PATHCONV=1` when running `docker` directly from Git Bash. `docker compose` itself isn't affected (paths in `docker-compose.yml` aren't shell arguments), only ad hoc `docker run`/`docker exec` invocations are.

**`tempo` fails to start with `not a directory ... Are you trying to mount a directory onto a file` pointing at the *host* path.**
Misleading error — the problem is the *container-side* path, not the host one: in the `grafana/tempo` image, `/tempo` is the Tempo **binary** (its entrypoint), so mounting a data directory there collides with a file that already exists in the image. The compose file mounts data at `/var/tempo` for exactly this reason; if you change it, don't change it back to `/tempo`.

**Traces aren't appearing in Tempo/Grafana.**
Check any app service's first log lines for the `otel.javaagent` banner (agent attached) and for exporter warnings. The agent defaults to `http/protobuf` on port 4318; this stack explicitly sets `OTEL_EXPORTER_OTLP_PROTOCOL=grpc` to match Tempo's `4317` receiver — if you see a "port is likely incorrect for protocol" warning, those two env vars have drifted apart.

---

## Author

**Praveenreddy Somireddy** — design, implementation, infrastructure, and
documentation.

The [case studies](docs/case-study/) are the honest version of this README:
they record the defects found in production, the reviews that reversed
decisions, and the things that are deliberately not built. Where this file
says a thing works, that document says how it broke first.
