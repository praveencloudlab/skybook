# 🐳 SkyBook Dockerization — Design

---

## Project Information

| | |
|---|---|
| **Scope** | `docker-compose.yml` + one `Dockerfile` per Spring Boot module + Postgres/Kafka init |
| **Branch** | `feature/dockerization` |
| **Status** | Implemented and verified per §13/§14. |

Goal: `docker compose up --build` from a clean clone starts PostgreSQL, Kafka, and all eight Spring Boot services (`api-gateway`, `auth-service`, `flight-service`, `booking-service`, `inventory-service`, `payment-service`, `checkin-service`, `notification-service`), fully wired to each other, with no manual `CREATE DATABASE` or local Kafka install first. Today, per every service's own README/design doc, local dev requires a human to run `CREATE DATABASE skybook_x;` by hand against a local Postgres and have a Kafka broker already listening on `9092` — this branch replaces that with one command.

**`frontend/` is out of scope** — confirmed it's still just a `.gitkeep` placeholder (no app to containerize yet).

---

# Table of Contents

1. [Overview](#1-overview)
2. [Load-Bearing Findings](#2-load-bearing-findings)
3. [Architecture](#3-architecture)
4. [Dockerfile Strategy](#4-dockerfile-strategy)
5. [docker-compose.yml Shape](#5-docker-composeyml-shape)
6. [PostgreSQL](#6-postgresql)
7. [Kafka](#7-kafka)
8. [Configuration & Secrets](#8-configuration--secrets)
9. [Health Checks & Startup Ordering](#9-health-checks--startup-ordering)
10. [Volumes & Data Persistence](#10-volumes--data-persistence)
11. [Deferred / Out of Scope](#11-deferred--out-of-scope)
12. [Known Risks / Open Questions](#12-known-risks--open-questions)
13. [Build Order](#13-build-order)
14. [Testing / Verification Plan](#14-testing--verification-plan)
15. [Implementation Notes](#15-implementation-notes)

---

# 1. Overview

Ten containers, one `docker-compose.yml`, one bridge network (Compose's default — no custom network block needed at this scale):

- **Infra (2):** `postgres` (one instance, six databases), `kafka` (one broker, KRaft mode, no ZooKeeper)
- **App services (8):** `api-gateway` (8080), `auth-service` (8081), `flight-service` (8082), `booking-service` (8083), `inventory-service` (8084), `notification-service` (8085), `payment-service` (8086), `checkin-service` (8087)

Every app service already reads its port from `application.yml` (confirmed — no two services share a port) and connects to Postgres/Kafka via `localhost` today. Making that work in Compose is entirely a **configuration** problem, not a code problem, with one exception (§2).

---

# 2. Load-Bearing Findings

Confirmed by reading every service's actual `pom.xml`/`application.yml`, not assumed:

1. **Three services have no Actuator.** `auth-service`, `flight-service`, and `notification-service` depend on `spring-boot-starter-web` but **not** `spring-boot-starter-actuator` — the other five (`booking`, `inventory`, `payment`, `checkin`, `api-gateway`) already have it with `management.endpoints.web.exposure.include: health,info,metrics`. Without Actuator there's no `/actuator/health` for Docker's `healthcheck:` to hit. **Decision: add `spring-boot-starter-actuator` + the same `management.endpoints` block to these three services**, mirroring what the other five already do — a small, additive, in-pattern change, same shape as the `auth-service` port fix made in `feature/api-gateway`.

2. **`.gitignore` already commits to bind-mount directory names.** It already ignores `docker-data/`, `postgres-data/`, and `kafka-data/`, plus `.env` and `.env.*`. Nobody added those speculatively for this doc — they predate this branch. **Decision: honor those exact names** rather than inventing new ones (`./docker-data/postgres`, `./docker-data/kafka` as bind mounts — see §10) and use a template env file named `env.example` (no leading dot, so it doesn't get swallowed by the existing `.env.*` ignore rule) rather than the more conventional `.env.example`.

3. **No Flyway migrations actually exist.** `auth-service` depends on `flyway-core`/`flyway-database-postgresql`, but there's no `src/main/resources/db/migration` directory at all. Flyway will run at startup and do nothing (empty migration set is valid, not an error) — **every service's schema is actually created by Hibernate's `ddl-auto: update`**, confirmed in all six datasource-owning services' `application.yml`. This means Postgres init only needs to create six empty databases, no DDL/seed scripts.

4. **Inter-service URLs are hardcoded `http://localhost:PORT` literals, not `${...}` placeholders** — `booking-service`'s `flight-service.base-url`, `inventory-service.base-url`; `inventory-service`'s `flight-service.base-url`; `checkin-service`'s `flight-service.base-url`/`inventory-service.base-url`; `api-gateway`'s six `services.*.base-url` keys. None of these need source changes: Spring Boot's environment-variable relaxed binding overrides any property from a container `environment:` entry (`flight-service.base-url` ⟷ env var `FLIGHT_SERVICE_BASE_URL`; `services.flight-service.base-url` ⟷ `SERVICES_FLIGHT_SERVICE_BASE_URL`), the same mechanism `spring.datasource.url` ⟷ `SPRING_DATASOURCE_URL` already relies on. **Decision: override every cross-service URL and every Postgres/Kafka connection string via Compose `environment:` entries, zero YAML edits in any service.**

5. **Kafka client is 3.9.2** (confirmed via `mvn dependency:tree`). No `NewTopic`/`KafkaAdmin` bean exists anywhere in the codebase — every topic (`skybook.payment.events`, `skybook.booking.events`, `skybook.checkin.events`, `skybook.email.events`) is created lazily today via the broker's `auto.create.topics.enable` default. **Decision: keep relying on auto-creation** (the official `apache/kafka` image ships that default `true`) rather than introducing an explicit topic-provisioning step — no behavior change from what local dev already does.

6. **Docker Desktop is already installed and already in use** — `docker --version` → 29.5.3, `docker compose version` → v5.1.4, and `payment-service`'s own README already says its JPA/Kafka integration tests need Docker (Testcontainers) and skip without it. Nothing new to install for this branch.

---

# 3. Architecture

```
                        docker compose up --build
                                  │
        ┌─────────────────────────┴─────────────────────────┐
        │                  default bridge network             │
        │                                                     │
        │   postgres:5432          kafka:9092                 │
        │   (6 databases,          (KRaft, single              │
        │    one instance)          broker, no ZK)             │
        │        ▲   ▲                  ▲   ▲                  │
        │        │   │                  │   │                 │
        │   ┌────┴───┴──────────────────┴───┴──────────────┐  │
        │   │ auth(8081) flight(8082) booking(8083)         │  │
        │   │ inventory(8084) notification(8085)            │  │
        │   │ payment(8086) checkin(8087)                   │  │
        │   └───────────────────────┬────────────────────────┘  │
        │                           │ (proxied by)                │
        │                    api-gateway (8080)                   │
        └─────────────────────────┬─────────────────────────┘
                                    │
                          host: localhost:8080
```

`api-gateway` is the only container the host needs to reach directly for API calls (mirrors `feature/api-gateway`'s trust-boundary design — the eight services stay on the compose network, not individually published to the host, though for local dev convenience §11 discusses whether to still publish their ports).

---

# 4. Dockerfile Strategy

One `Dockerfile` per service (`backend/<service>/Dockerfile`), structurally identical multi-stage template — differs only in which module gets built and which port is `EXPOSE`d:

```dockerfile
# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /workspace
COPY pom.xml .
COPY skybook-common skybook-common
COPY <service> <service>
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -pl <service> -am package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=builder /workspace/<service>/target/*.jar app.jar
EXPOSE <port>
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

Decisions, each with a reason:

- **`maven:3.9.9-eclipse-temurin-21` builder, not `./mvnw`** — confirmed no Maven wrapper exists anywhere in the repo; adding one is a separate, orthogonal change. Using the official Maven image needs no repo changes and pins the exact Java 21 this project already targets everywhere (`<java.version>21</java.version>` in every module).
- **`mvn -pl <service> -am package`, not a full reactor build** — only builds the target module plus `skybook-common` (its one real inter-module dependency, confirmed — no service depends on any other service's jar), so each service's image only rebuilds what it actually needs.
- **Build context is `./backend`, not each service's own directory** — every Dockerfile's `COPY skybook-common skybook-common` needs `skybook-common` to be inside the build context, so Compose sets `context: ./backend`, `dockerfile: <service>/Dockerfile` per service (§5). Requires a `.dockerignore` at `backend/.dockerignore` (`*/target/`, `.idea/`, `*.iml`) so the ~9-module reactor's build artifacts and IDE metadata don't get shipped into every build context — `api-gateway/target` alone is already ~900 files.
- **`--mount=type=cache,target=/root/.m2`** — BuildKit cache mount so re-running `docker compose up --build` after a small code change doesn't re-download the entire dependency tree every time; needs BuildKit, on by default in the installed Docker 29.5.3/Compose v5.1.4.
- **`eclipse-temurin:21-jre-jammy` runtime, not `-alpine`** — Jammy (Ubuntu) is a safer default than Alpine's musl libc given `notification-service` pulls in ZXing and openhtmltopdf/PDFBox (font/graphics-adjacent native-sensitive libraries); a few extra MB is an acceptable trade for not debugging Alpine-specific rendering quirks in a portfolio project. Alpine is a valid future size optimization, not built here (§11).
- **`curl` added in the runtime stage** — needed for the container's own `healthcheck:` (§9); Jammy's JRE base doesn't ship it.
- **`JAVA_OPTS` passed through via `sh -c`, empty by default** — costs nothing today, gives a documented seam for `-Xmx`/GC tuning later without a rebuild, deferred (§11) rather than guessing at values with no load-testing behind them.

---

# 5. docker-compose.yml Shape

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    volumes:
      - ./docker-data/postgres:/var/lib/postgresql/data
      - ./docker/postgres/init-databases.sql:/docker-entrypoint-initdb.d/init-databases.sql:ro
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 5s
      timeout: 5s
      retries: 10

  kafka:
    image: apache/kafka:3.9.0
    environment:
      # KRaft single-node broker+controller — see §7
    volumes:
      - ./docker-data/kafka:/var/lib/kafka/data
    ports:
      - "9092:9092"
    healthcheck:
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092"]
      interval: 10s
      timeout: 5s
      retries: 10

  auth-service:
    build:
      context: ./backend
      dockerfile: auth-service/Dockerfile
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/skybook_auth
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      JWT_SECRET: ${JWT_SECRET}
    depends_on:
      postgres: { condition: service_healthy }
      kafka: { condition: service_healthy }
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8081/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s

  # flight-service, booking-service, inventory-service, payment-service,
  # checkin-service, notification-service: same shape, each with its own
  # SPRING_DATASOURCE_URL / base-url overrides per §8's table.

  api-gateway:
    build:
      context: ./backend
      dockerfile: api-gateway/Dockerfile
    ports:
      - "8080:8080"
    environment:
      JWT_SECRET: ${JWT_SECRET}
      SERVICES_AUTH_SERVICE_BASE_URL: http://auth-service:8081
      SERVICES_FLIGHT_SERVICE_BASE_URL: http://flight-service:8082
      SERVICES_BOOKING_SERVICE_BASE_URL: http://booking-service:8083
      SERVICES_INVENTORY_SERVICE_BASE_URL: http://inventory-service:8084
      SERVICES_PAYMENT_SERVICE_BASE_URL: http://payment-service:8086
      SERVICES_CHECKIN_SERVICE_BASE_URL: http://checkin-service:8087
    # No depends_on on the app services themselves — DownstreamErrorHandlingFilter
    # (feature/api-gateway) already turns "downstream not up yet" into a clean
    # 502 instead of the gateway failing to start, so there's nothing to gate on.
```

(Full file has all eight app services spelled out at implementation time; this section shows the pattern once plus the two infra services and the gateway's full env block since it's the most complex.)

---

# 6. PostgreSQL

- **One `postgres:16-alpine` instance, six databases** (`skybook_auth`, `skybook_flight`, `skybook_booking`, `skybook_inventory`, `skybook_payment`, `skybook_checkin`) — matches what's already manually created today per-service, confirmed from each service's `application.yml` datasource URL and `payment-service/README.md`'s explicit `CREATE DATABASE skybook_payment;` instruction.
- `docker/postgres/init-databases.sql`, mounted read-only into `/docker-entrypoint-initdb.d/`, runs once on first container start (empty data directory) — six `CREATE DATABASE` statements, nothing else (no DDL — Hibernate owns that per finding §2.3).
- Shared credentials `postgres`/`postgres` across all six databases — matches every service's current hardcoded `spring.datasource.username`/`password`, not changed here to keep this a config-only, no-source-edit branch. Flagged in §12 as a known, accepted dev-only weakness.

---

# 7. Kafka

- **`apache/kafka:3.9.0`, KRaft mode, single node acting as both broker and controller — no ZooKeeper.** ZooKeeper is deprecated (optional as of Kafka 3.3, removed as the default path in newer lines); a brand-new setup has no reason to bring it back, and this matches the single-broker, single-controller shape this dev environment actually needs.
- `auto.create.topics.enable` stays at the image's default (`true`) — see finding §2.5. No topic-provisioning init container.
- Advertised listeners need `kafka:9092` (for other containers) — the exact KRaft env var block (`KAFKA_NODE_ID`, `KAFKA_PROCESS_ROLES`, `KAFKA_LISTENERS`, `KAFKA_ADVERTISED_LISTENERS`, `KAFKA_CONTROLLER_QUORUM_VOTERS`, `CLUSTER_ID`) will be finalized and verified empirically against this image's actual expected env vars during implementation (§13 step 1) rather than guessed here — this is exactly the kind of thing the api-gateway design doc's own philosophy says to confirm empirically, not assume.

---

# 8. Configuration & Secrets

| Variable | Used by | Source |
|---|---|---|
| `JWT_SECRET` | `auth-service`, `api-gateway` | `.env` (must be identical in both — same risk already documented in `API_GATEWAY_MODULE.md` §11, now also a Compose concern) |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | `notification-service` | `.env` (Gmail SMTP creds, already `${...}`-templated in its `application.yml`) |
| `CHECKIN_BOARDING_PASS_KEY` | `checkin-service` | `.env`, optional — already has a safe dev-only default in `application.yml` |
| `SPRING_DATASOURCE_URL` | every DB-owning service | hardcoded per-service in `docker-compose.yml` (not secret, just environment-specific) |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | every Kafka-using service | hardcoded per-service in `docker-compose.yml` |
| `*_BASE_URL` (six on the gateway, one or two each on booking/inventory/checkin) | cross-service HTTP calls | hardcoded per-service in `docker-compose.yml` |

`env.example` (repo root, tracked in git — named to dodge the existing `.env.*` ignore rule, finding §2.2) lists `JWT_SECRET`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `CHECKIN_BOARDING_PASS_KEY` with placeholder values and a one-line comment each; a developer copies it to `.env` (gitignored) before their first `docker compose up`.

---

# 9. Health Checks & Startup Ordering

- Every app service gets a `curl -f http://localhost:<port>/actuator/health` healthcheck once it has Actuator (finding §2.1 closes this gap for the three services that were missing it).
- `depends_on: { condition: service_healthy }` from every app service to whichever of `postgres`/`kafka` it actually uses (confirmed per-service from each `application.yml` — e.g. `flight-service` has no Kafka dependency at all, so it only waits on `postgres`).
- **No app-to-app `depends_on`.** `booking-service` calls `flight-service`/`inventory-service`, `inventory-service` and `checkin-service` call `flight-service`, and `api-gateway` calls everything — but none of that is a hard *startup-order* requirement given the whole fleet already tolerates a downstream 5xx at request time (booking/inventory's own synchronous-call error handling, plus the gateway's 502 translation). Compose still starts them in a reasonable order by declaration, but correctness doesn't depend on it.
- `start_period: 30s` on app services — Spring Boot + Hibernate `ddl-auto: update` cold start plus JVM startup is confirmed to take several seconds locally; 30s avoids flapping a container to "unhealthy" before it's had a fair chance, tuned empirically during implementation if needed.

---

# 10. Volumes & Data Persistence

Bind mounts, not Docker named volumes — directory names taken directly from the pre-existing `.gitignore` (finding §2.2), not invented here:

- `./docker-data/postgres:/var/lib/postgresql/data`
- `./docker-data/kafka:/var/lib/kafka/data`

`docker compose down` keeps this data (bind-mounted host directories survive container removal); `docker compose down -v` or manually deleting `./docker-data/` is the reset path — documented explicitly in the troubleshooting notes (§13 step 8) since it's an easy footgun otherwise.

---

# 11. Deferred / Out of Scope

- **Publishing every app service's port to the host** (`8081`-`8087`) vs. keeping them compose-network-only. Leaning toward *still publishing them* for local dev convenience (Postman collections already target `localhost:808x` directly per-service) even though `feature/api-gateway`'s trust-boundary model (§8 of that doc) says only the gateway should be reachable — that model was explicitly scoped to a *real* deployment, not this dev Compose file. Final call made during implementation, not guessed here; either way is a one-line change per service.
- **TLS/HTTPS anywhere** — nothing in the fleet does TLS today; out of scope for a local dev Compose file.
- **JVM memory/GC tuning, container `mem_limit`/`cpus`** — no load testing has happened yet to justify specific numbers; `JAVA_OPTS` pass-through (§4) is the seam for this later.
- **Kafka multi-broker / replication**, **Postgres replication/backups** — single-node dev setup only.
- **CI/CD building these images** — that's `feature/ci-cd`'s job, not this branch's.
- **Frontend container** — nothing to containerize yet (confirmed `.gitkeep` only).
- **Alpine runtime images** — deferred size optimization, not built now (§4).
- **Secrets management (Vault/K8s Secrets)** — `.env` + `env.example` is the right amount of ceremony for a local/portfolio Compose setup; revisit only if this ever targets a real multi-developer or cloud environment.

---

# 12. Known Risks / Open Questions

- **`JWT_SECRET` drift** between `auth-service` and `api-gateway` containers — same risk `API_GATEWAY_MODULE.md` §11 already flagged for local (non-Docker) dev; a single `.env` file read by both via Compose actually *reduces* this risk versus two developers manually exporting the same env var in two terminals.
- **Shared Postgres superuser credentials (`postgres`/`postgres`) across all six databases** — matches what every service already hardcodes today; not hardened here to keep this branch config-only. Would need per-service credentials + a real secrets story before this setup is anything but a dev/portfolio environment.
- **First-run Kafka/Postgres init timing** — `docker-entrypoint-initdb.d` and KRaft cluster formation both add real seconds to a *first* `docker compose up --build`; subsequent runs are faster since the bind-mounted data persists. Documented in troubleshooting notes, not treated as a bug.
- **`.dockerignore` correctness** — if it's too narrow, build contexts balloon with stale `target/` directories (slow builds); if too broad, it could exclude something a Dockerfile actually needs to `COPY`. Verified empirically per service during implementation, not assumed correct on the first try.

---

# 13. Build Order

1. **Kafka KRaft env block, verified empirically** — start `kafka` alone, confirm the broker actually comes up and `kafka-broker-api-versions.sh` succeeds, before wiring anything else to it (§7 explicitly defers the exact env vars to this step).
2. **Postgres + init script** — `docker compose up postgres`, verify all six databases exist (`psql -U postgres -c '\l'`).
3. **`.dockerignore`** at `backend/.dockerignore`.
4. **One template service end-to-end** — `flight-service` (simplest: Postgres only, no Kafka, no cross-service calls) gets its `Dockerfile`, builds, runs, and its `/actuator/health` responds *after* step 5 adds Actuator to it.
5. **Close the Actuator gap** (finding §2.1) — add `spring-boot-starter-actuator` + `management.endpoints` to `auth-service`, `flight-service`, `notification-service`.
6. **Roll the Dockerfile template out** to the remaining seven services.
7. **Full `docker-compose.yml`** — every service, every env override from §8, every healthcheck and `depends_on` from §9.
8. **`docker compose up --build` from a clean clone** — verify the whole platform comes up, then exercise one real request through it (login via `api-gateway` → protected route on a downstream service) to prove the network wiring, not just "containers are green." Write up the troubleshooting notes (data-reset path, common first-run timing issues) alongside the run instructions at this point, matching this doc's own §12 risks.
9. **Update this doc's status** to implemented + an Implementation Notes section, same pattern `API_GATEWAY_MODULE.md` §14 followed.

---

# 14. Testing / Verification Plan

This branch is infrastructure configuration, not application code — there's nothing here to unit-test. Verification is operational, done once at the end of §13's build order and re-checked any time the compose file changes materially:

| Check | How |
|---|---|
| Clean-clone startup | `docker compose up --build` from a fresh clone (no `./docker-data/`, no `.env` except a copy of `env.example`) succeeds with no manual steps |
| All containers healthy | `docker compose ps` shows every service `healthy`, not just `running` |
| Cross-service call works | A login through `api-gateway` followed by a call to a JWT-protected route that itself calls another service synchronously (e.g. create a booking, which validates against `flight-service`/`inventory-service`) succeeds |
| Kafka event flow works | An action that produces an event consumed by another container (e.g. a booking confirmation triggering `notification-service`) is observed to actually happen, not just "the topic exists" |
| Data survives a restart | `docker compose restart <service>` (not `down -v`) keeps existing rows/messages |
| Reset path works | Deleting `./docker-data/` and re-running `--build` gets back to a clean, fully-working state |

---

# 15. Implementation Notes

Built per §13's order. The design held up structurally, but four real problems only surfaced once containers were actually wired together and run - each is the kind of thing this doc's own philosophy says to confirm empirically rather than assume:

**1. `mvn package` never produced a runnable jar, in any of the 8 services - found on the very first Docker build attempt.** This project doesn't inherit from `spring-boot-starter-parent` (only imports the `spring-boot-dependencies` BOM), so nothing auto-binds the `spring-boot-maven-plugin`'s `repackage` goal to the `package` phase the way `starter-parent` normally would. Every service's own docs only ever documented `mvn spring-boot:run` (which doesn't need repackaging), so this had never been hit before. `flight-service`'s first Docker build produced a 62KB plain jar with no `Main-Class` - `docker run` failed with `no main manifest attribute, in app.jar`. Fixed once, in `backend/pom.xml`'s `pluginManagement`, binding `repackage` for every module that already declares the plugin (all 8 services did, for their lombok-exclude config) - no per-service edits needed. Verified: every service's jar now carries `Main-Class`/`Start-Class`/`Spring-Boot-Version` in its manifest and is 50-60MB (a real fat jar).

**2. Maven's reactor validates every declared `<module>` directory exists, regardless of `-pl`/`-am` scoping.** The design's Dockerfile template originally `COPY`d only `pom.xml`, `skybook-common`, and the target service into the build context - Maven refused to even parse the root POM ("Child module /workspace/auth-service ... does not exist") because the reactor pom lists all 9 modules. Fixed by copying the whole `backend/` directory into every build context instead (still only *builds* `skybook-common` + the target module via `-pl -am`; the other 7 directories just need to exist for Maven's own validation, they're never compiled). `.dockerignore` keeps this cheap - build context is ~1.3MB per service, not gigabytes, since `target/` is excluded.

**3. Postgres's first-ever cold start runs a temporary bootstrap server, which raced with `depends_on: condition: service_healthy`.** On a truly first run (empty `./docker-data/postgres`), the official image starts a *temporary* single-use Postgres instance to run `docker-entrypoint-initdb.d/*` scripts, shuts it down, then starts the real server - observed to take up to ~19 seconds end-to-end. The `pg_isready` healthcheck succeeded against the *temporary* server (it's a real, if short-lived, Postgres instance - nothing distinguishes it from the outside), so Compose released `depends_on` and started every DB-owning service while Postgres was mid-restart. Every one of them crashed with `HikariPool.checkFailFast()` throwing on `Connection refused` - a hard, eager failure at pool construction time. Rather than chase healthcheck timing heuristics (which can't reliably tell "temporary" from "real" Postgres from the outside), fixed at the source of the actual crash: every DB-owning service gets `SPRING_DATASOURCE_HIKARI_INITIALIZATION_FAIL_TIMEOUT: "-1"` in `docker-compose.yml`, which disables Hikari's eager fail-fast check. Connections are then established lazily on first real use (Flyway/Hibernate), with HikariCP's normal retry-with-backoff up to `connection-timeout` (default 30s) - comfortably longer than Postgres's one-time restart window. Verified: a from-scratch `docker compose up --build` against a deleted `./docker-data/` now brings every service to `healthy` with no crashes.

**4. `notification-service` could never report healthy with placeholder SMTP credentials.** Actuator auto-configures a `MailHealthIndicator` once `spring-boot-starter-mail` is on the classpath, which actually opens a connection to `smtp.gmail.com` as part of `/actuator/health`. With the test `.env`'s placeholder Gmail credentials, this indicator reported `DOWN`, which Spring Boot's default health aggregation rolled up into the whole service being `DOWN` - even though Kafka consumption (the thing this service actually does) had nothing to do with SMTP. Fixed with `management.health.mail.enabled: false` in `notification-service`'s `application.yml`, so mail connectivity is no longer part of the container's own liveness signal (a developer without working Gmail credentials still gets a fully-`healthy` platform; email sends will just fail, which is the correct scope for a health check that answers "is the app up," not "can it currently reach Gmail").

**Verification actually performed**, beyond the checklist in §14:
- Real end-to-end round trip through the live stack: `POST /api/auth/register` → `POST /api/auth/login` → `GET /api/flights` through `api-gateway` returns 401 with no token and 200 with the token issued by `auth-service` - proving `JWT_SECRET` is correctly shared between the two containers via `.env`.
- Kafka: confirmed via `kafka-topics.sh --list` that all four topics (`skybook.payment.events`, `skybook.booking.events`, `skybook.checkin.events`, `skybook.email.events`) exist, and via `payment-service`'s own logs that its consumer (`groupId=payment-service`) subscribed successfully with `bootstrap.servers=[kafka:9092]` - confirming the env-var override resolved correctly.
- Restarted `auth-service` mid-session and confirmed the user registered earlier could still log in afterward - Postgres data survives a container restart (bind mount, not an ephemeral layer).
- Full reactor `mvn test` (all 9 modules) re-run after every source change in this branch (the plugin binding, the three Actuator additions, the `SecurityConfig` change, the mail health-indicator disable) - zero regressions, same test counts as before this branch.

**One environment-specific note, not a code issue:** on Windows with Git Bash, `docker run`/`docker exec` mangle absolute paths passed as arguments (including inside `-v host:container` mount specs), silently turning a single-file bind mount into an empty directory. `docker compose` itself is unaffected (compose file paths aren't shell arguments). Documented in the root README's troubleshooting section since it cost real debugging time during this branch's manual verification, even though it doesn't affect `docker compose up` itself.
