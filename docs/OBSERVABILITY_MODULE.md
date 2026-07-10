# 🔭 SkyBook Observability — Design

---

## Project Information

| | |
|---|---|
| **Scope** | Metrics (Prometheus), dashboards (Grafana), centralized logs (Loki), distributed traces (OpenTelemetry → Tempo), correlation propagation across HTTP + Kafka |
| **Branch** | `feature/observability` |
| **Status** | Frozen (with one review revision: observability dependencies are declared per-service, not via `skybook-common` — §4/§6). Implementation starting per §11. |

Goal: a failure anywhere in Gateway → Booking → Inventory → Payment → Check-in is traceable from one place. Today, each container writes unstructured console logs that die with `docker compose logs`' scrollback, metrics exist only as unexposed Actuator endpoints nobody scrapes, and the gateway's `X-Correlation-Id` is logged exactly once — at the gateway — then ignored by every downstream service.

---

# Table of Contents

1. [Overview](#1-overview)
2. [Load-Bearing Findings](#2-load-bearing-findings)
3. [Architecture](#3-architecture)
4. [Metrics: Micrometer → Prometheus](#4-metrics-micrometer--prometheus)
5. [Traces & Correlation: OpenTelemetry → Tempo](#5-traces--correlation-opentelemetry--tempo)
6. [Logs: JSON → Promtail → Loki](#6-logs-json--promtail--loki)
7. [Grafana](#7-grafana)
8. [Configuration & Ports](#8-configuration--ports)
9. [Deferred / Out of Scope](#9-deferred--out-of-scope)
10. [Known Risks / Open Questions](#10-known-risks--open-questions)
11. [Build Order](#11-build-order)
12. [Testing / Verification Plan](#12-testing--verification-plan)

---

# 1. Overview

Four new infra containers join `docker-compose.yml` (Prometheus, Grafana, Loki, Tempo, plus the Promtail log shipper — five processes, all single-node), and every app service gains three cross-cutting capabilities with almost no application code:

- **Metrics**: `/actuator/prometheus` on every service, scraped by Prometheus.
- **Traces + correlation**: the OpenTelemetry Java agent attached to every service JVM — automatic HTTP client/server, JDBC, and Kafka producer/consumer instrumentation, W3C `traceparent` propagation across both sync (Feign) and async (Kafka) hops, exported OTLP to Tempo. The agent also injects `trace_id`/`span_id` into the logging MDC, which is what stitches logs to traces.
- **Centralized logs**: JSON-structured logs (one shared logback config), collected from Docker's container log files by Promtail, stored in Loki, queried in Grafana — filterable by service, level, `trace_id`, or the gateway's `X-Correlation-Id`.

Grafana is the single pane: Prometheus + Loki + Tempo as provisioned datasources, logs↔traces cross-linked by `trace_id`.

---

# 2. Load-Bearing Findings

Confirmed by inspection, not assumed:

1. **Actuator alone exposes no Prometheus endpoint.** All 8 services have `spring-boot-starter-actuator` with `health,info,metrics` exposed (finished in `feature/dockerization`), and Micrometer-core ships inside Actuator — but the Prometheus *registry* (`micrometer-registry-prometheus`) exists nowhere in the reactor, so there is no `/actuator/prometheus` to scrape. One dependency + one exposure-list entry per service.
2. **The correlation id dies at the first hop.** `api-gateway`'s `RequestLoggingFilter` generates/propagates `X-Correlation-Id` downstream and logs it — but no downstream service reads it (no MDC usage anywhere in `backend/*/src/main/java`), no Feign `RequestInterceptor` exists (so booking→flight, booking→inventory, checkin→flight/inventory, inventory→flight sync calls drop it), and none of the six shared Kafka event types carry it (checkin/payment's `correlationId` fields are domain-audit fields on history entities, unrelated to request tracing). Rather than hand-building MDC filters + Feign interceptors + Kafka header plumbing across 8 services, the OTel agent's automatic `traceparent` propagation covers every hop — HTTP and Kafka — with zero application code (§5). The gateway's `X-Correlation-Id` stays untouched as a complementary business-facing id.
3. **No logging configuration exists at all.** No `logback*.xml` in any service — every container writes Spring Boot's default human-oriented console pattern, which is painful to parse reliably. JSON logs via one shared logback include (living in `skybook-common`, which all 8 services already depend on) beats maintaining 8 copies and beats fragile regex parsing in the collector (§6).
4. **The `JAVA_OPTS` seam from `feature/dockerization` is exactly the agent-attachment point.** Every Dockerfile's entrypoint is `java $JAVA_OPTS -jar app.jar` — attaching the OTel agent is a compose-level `JAVA_OPTS: -javaagent:/otel/opentelemetry-javaagent.jar` plus env vars, no Dockerfile change and trivially removable per service.
5. **Port 3000 is reserved in spirit.** The gateway's CORS config lists `http://localhost:3000` as an expected future frontend dev origin. Grafana defaults to 3000 — mapped to **3001** on the host instead, so the eventual `feature/frontend` doesn't collide with it (§8).
6. **Loki over ELK** — decided, not open: Grafana-native (no second UI), a single small Go binary vs. an Elasticsearch JVM heap, and label-based indexing is plenty for one machine's log volume. ELK's power (full-text indexing at scale) solves a problem this project doesn't have.

---

# 3. Architecture

```
                          ┌───────────────────────── Grafana :3001 ─────────────────────────┐
                          │  datasources: Prometheus + Loki + Tempo (logs ↔ traces linked)  │
                          └──────▲──────────────────────▲──────────────────────▲────────────┘
                                 │                      │                      │
                     Prometheus :9090             Loki :3100             Tempo :3200
                       (scrapes)                 (stores logs)         (stores traces)
                                 │                      ▲                      ▲
        /actuator/prometheus ────┘                      │                      │ OTLP gRPC :4317
        on all 8 services                        Promtail (reads               │
                                                 docker json-file             │
                                                 logs of all containers)      │
                                                        │                      │
   ┌────────────────────────────────────────────────────┴──────────────────────┴───┐
   │  8 × app service, each with:                                                   │
   │   - micrometer-registry-prometheus (declared per service)                      │
   │   - OTel Java agent (-javaagent via JAVA_OPTS): HTTP/JDBC/Kafka auto-          │
   │     instrumentation, traceparent propagation, trace_id/span_id → MDC           │
   │   - JSON logback config: per-service encoder dep + 3-line include of the       │
   │     shared base file, incl. trace_id, span_id, X-Correlation-Id when present   │
   └────────────────────────────────────────────────────────────────────────────────┘
```

A request's story: gateway assigns `X-Correlation-Id` (existing behavior) and the OTel agent starts a trace → every sync Feign hop and async Kafka hop carries `traceparent` automatically → every log line everywhere carries `trace_id` → in Grafana, filter Loki by any service's error, click the `trace_id`, land in the Tempo waterfall showing the full Gateway → Booking → Inventory → Payment → Check-in path with per-hop latency.

---

# 4. Metrics: Micrometer → Prometheus

- **`micrometer-registry-prometheus` declared explicitly in each of the 8 service poms** (version managed by the Spring Boot BOM). The first draft routed it transitively through `skybook-common`; review reversed that — `skybook-common` is a *domain* library (events, exceptions), and smuggling runtime infrastructure in as a hidden transitive dependency couples every consumer invisibly. Eight explicit one-liner declarations keep each service's runtime surface readable in its own pom, the same way each already declares its own actuator/web/kafka starters.
- `management.endpoints.web.exposure.include` gains `prometheus` in each service's `application.yml` (8 small edits — exposure lists are per-service config, deliberately not centralized).
- **Prometheus container** (`prom/prometheus`), one static scrape config listing the 8 compose service names — same static-config convention as everything else in this fleet (no service discovery exists, none needed). 15s scrape interval, bind-mounted config at `docker/prometheus/prometheus.yml`, data in `./docker-data/prometheus`.
- What this buys immediately, with zero custom instrumentation: JVM memory/GC/threads, HTTP server request rates/latencies per endpoint per status, HikariCP pool stats, Kafka consumer lag-adjacent client metrics, Tomcat threads — all standard Spring Boot Micrometer bindings that activate the moment the registry exists.
- Custom business metrics (bookings created, payments captured, check-ins completed…) are **deferred** (§9) — the platform pipe comes first; domain metrics are per-service feature work once the pipe exists.

# 5. Traces & Correlation: OpenTelemetry → Tempo

- **OTel Java agent, attached via `JAVA_OPTS`** (finding §2.4) — not Micrometer Tracing/manual SDK wiring. The agent auto-instruments Spring MVC server spans, RestClient/Feign client spans, JDBC, and — critically — **Kafka producers/consumers with `traceparent` header propagation**, which manual approaches only get with per-service code. Zero application code, one env block per service in compose, and turning it off is deleting two env lines.
- Agent jar delivery: **baked into each image via a `ADD` of the pinned GitHub release** in the Dockerfile builder... **no — decided against**: that changes 8 Dockerfiles and bloats images even when tracing is off. Instead a tiny **`otel-agent` init volume**: a one-shot compose service (`curlimages/curl`) downloads the pinned agent jar (checksum-verified) into a named volume once; app services mount that volume read-only at `/otel/`. Restart-safe, version pinned in one place, images untouched.
- Export: OTLP gRPC to **Tempo** (`grafana/tempo`, single binary, local storage in `./docker-data/tempo`). Tempo over Jaeger because Grafana is already the UI (§7) — no second tracing UI to run.
- Config per service (compose env, identical block): `OTEL_SERVICE_NAME=<service>`, `OTEL_EXPORTER_OTLP_ENDPOINT=http://tempo:4317`, `OTEL_TRACES_EXPORTER=otlp`, metrics/logs exporters `none` (Prometheus and Loki own those paths), plus `OTEL_INSTRUMENTATION_LOGBACK_MDC_ENABLED=true` so `trace_id`/`span_id` land in the MDC for §6.
- The gateway's existing `X-Correlation-Id` behavior is untouched — it remains the human-friendly id surfaced to clients, while `traceparent` is the machine id that actually survives every hop. A one-line MDC put in the gateway's `RequestLoggingFilter` (the only application-code change in this section) makes the correlation id appear in the gateway's JSON logs alongside `trace_id`, linking the two id spaces at their origin.

# 6. Logs: JSON → Promtail → Loki

- **One shared logback base file in `skybook-common`** (`src/main/resources/skybook-logback-base.xml`), pulled into each service by a 3-line `logback-spring.xml` doing `<include resource="skybook-logback-base.xml"/>` — the standard logback include mechanism; 8 tiny files, one real config. The *resource file* living in `skybook-common` is deliberate and survives the review revision: a classpath XML carries zero dependency coupling — it's inert for any consumer that doesn't both declare the encoder dependency and write the include file. The **`logstash-logback-encoder` dependency itself is declared explicitly in each of the 8 service poms** (review revision, same reasoning as §4 — no hidden runtime infra through the domain library). Encoded fields: timestamp, level, logger, thread, message, stack traces, service name (from `spring.application.name`), `trace_id`/`span_id` (from the agent's MDC), `correlationId` (MDC, present at the gateway).
- Console (stdout) only — containers must not write log files; Docker's `json-file` driver already persists stdout per container, which is exactly what Promtail reads.
- **Promtail** with `docker_sd_configs` (Docker socket mounted read-only): discovers every compose container, tails its log file, labels streams with the compose service name, forwards to **Loki** (`grafana/loki`, single binary, filesystem storage in `./docker-data/loki`). Labels kept minimal (service, level) per Loki's own cardinality guidance — `trace_id` is queried by JSON filter expression, never a label.
- Non-app containers (postgres, kafka…) get collected too, as plain text — free coverage, no special handling.

# 7. Grafana

- `grafana/grafana` on host port **3001** (finding §2.5), admin password via `.env` (`GRAFANA_ADMIN_PASSWORD`, added to `env.example`), anonymous access off.
- **Provisioned as files, not clicked together**: datasource YAML for Prometheus/Loki/Tempo (with the Loki→Tempo `derived field` on `trace_id` and Tempo→Loki linkback, so logs↔traces navigation works out of the box), one starter dashboard JSON ("SkyBook Fleet": per-service request rate, error rate, p95 latency, JVM heap, plus a logs panel) bind-mounted from `docker/grafana/`. A fresh `docker compose up` yields a working Grafana with zero manual setup — same clone-and-run bar `feature/dockerization` set.
- Dashboard sprawl is deliberately resisted: one curated fleet dashboard now; per-service/domain dashboards are follow-up work once real usage shows what's worth charting (§9).

---

# 8. Configuration & Ports

| Container | Image | Host port | Data |
|---|---|---|---|
| prometheus | `prom/prometheus` | 9090 | `./docker-data/prometheus` |
| grafana | `grafana/grafana` | 3001 | `./docker-data/grafana` |
| loki | `grafana/loki` | 3100 (internal use; exposed for curl-debugging) | `./docker-data/loki` |
| tempo | `grafana/tempo` | 3200 (+4317 OTLP, compose-network only) | `./docker-data/tempo` |
| promtail | `grafana/promtail` | — | — (stateless; positions file in `./docker-data/promtail`) |

- All configs live under `docker/<component>/` (mirroring `docker/postgres/`), bind-mounted read-only.
- New `.env` entry: `GRAFANA_ADMIN_PASSWORD` (in `env.example` with a placeholder). Nothing else is secret — Prometheus/Loki/Tempo run unauthenticated inside the compose network, acceptable for local dev and flagged in §10.
- App services: no port changes; each gains the OTel env block + the shared JSON logging lands via the rebuilt images.

---

# 9. Deferred / Out of Scope

- **Custom business metrics** (bookings/day, payment success rate…) — per-service feature work once the pipe exists; belongs with domain sprints, not the platform branch.
- **Alerting** (Alertmanager / Grafana alerts) — needs baseline data to set thresholds against; natural fast-follow.
- **Per-service Grafana dashboards** beyond the one fleet dashboard (§7).
- **Log/trace retention tuning & compaction policies** — defaults are fine for a dev machine; revisit if `./docker-data` growth becomes annoying.
- **OTel metrics/logs pipelines** — the agent *could* also ship metrics and logs OTLP, but Prometheus scrape + Promtail are simpler, more standard for this stack shape, and independently debuggable; one signal type per pipe.
- **Kubernetes equivalents** (ServiceMonitors, OTel Operator…) — `feature/kubernetes`'s job; this branch is compose-only, same as everything before it.
- **Auth on Prometheus/Loki/Tempo** — compose-network-internal, dev-only (§10).

# 10. Known Risks / Open Questions

- **OTel agent version drift vs. Spring Boot 3.5.x** — the agent is pinned (checksum-verified download in the init service); upgrades are a one-line version bump, tested by just running the stack. If a specific instrumentation misbehaves, `OTEL_INSTRUMENTATION_<NAME>_ENABLED=false` disables it granularly without losing the rest.
- **Agent startup overhead** — the agent adds a few seconds of JVM startup and some memory; healthcheck `start_period` (30s from dockerization) may need bumping. Measured during implementation, not guessed.
- **Loki/Promtail on Windows Docker Desktop** — Promtail reads container logs through the mounted Docker socket/log paths inside the WSL2 VM; verified empirically in build order step 5 before anything depends on it (the Git Bash `MSYS_NO_PATHCONV` lesson from dockerization applies to any ad hoc debugging here).
- **Unauthenticated observability backends** — anyone on localhost can read metrics/logs/traces. Same trust model as the rest of the dev compose stack (documented in DOCKERIZATION_MODULE.md §12); Grafana is the only UI surface and it does have auth.
- **Per-service dependency declarations (review revision) trade one edit for eight** — the accepted cost of keeping `skybook-common` a pure domain library. The eight declarations are identical one-liners; drift risk is low and a new service copying any existing pom inherits the pattern.

---

# 11. Build Order

1. **Metrics pipe first** — Prometheus registry dep in all 8 service poms, `prometheus` exposure in all 8 `application.yml`s, Prometheus container + scrape config. Verify: `docker compose up`, Prometheus targets page shows 8/8 UP, `/actuator/prometheus` returns real JVM/HTTP series. (Full reactor `mvn clean verify` after the pom changes, per house rule.)
2. **Grafana + Prometheus datasource + fleet dashboard skeleton** — provisioned from files; verify a fresh volume-wipe still yields a working dashboard.
3. **Shared JSON logging** — logstash-logback-encoder in all 8 service poms, base config file in `skybook-common`, 8 include-only `logback-spring.xml`s, gateway's one-line correlation-id MDC put. Verify locally: log lines are valid JSON with service name; full test suite still green (tests log JSON too — cosmetic, but confirm nothing asserts on log format).
4. **Loki + Promtail** — verify in Grafana Explore: logs from all containers, filterable by service label, JSON fields parsed.
5. **OTel agent + Tempo** — init-volume download, env blocks on all 8 services, Tempo container, Grafana Tempo datasource + logs↔traces links. Verify end-to-end with a real business flow (login → create booking → payment → check-in via the gateway): one trace shows gateway + booking + inventory + flight spans (sync hops) and the Kafka-hop spans into payment/checkin/notification; log lines carry the same `trace_id`.
6. **README + env.example updates** — Grafana URL/creds, "how to find a request's trace" walkthrough, troubleshooting notes.
7. **Design doc → implemented + Implementation Notes**, per house pattern.

# 12. Testing / Verification Plan

| Check | How |
|---|---|
| All 8 scrape targets healthy | Prometheus `/targets` shows 8/8 UP after a clean `docker compose up --build` |
| Metrics are real | Fire requests through the gateway; watch `http_server_requests_seconds_count` increment for the right service/uri in Grafana |
| Logs centralized + structured | Grafana Explore → Loki: filter by `service`, expand a line, JSON fields (level, logger, trace_id) parsed |
| Trace spans every hop type | One booking flow produces a single trace containing: gateway span → booking span → Feign client spans → inventory/flight server spans → Kafka producer span → payment/checkin consumer spans |
| Logs ↔ traces linked | Click `trace_id` on a Loki log line → Tempo waterfall opens; from a Tempo span → related logs |
| Correlation id still works | `X-Correlation-Id` sent by the client appears in the gateway's JSON logs next to the `trace_id` of the same request |
| Clean-clone bar preserved | Wipe `./docker-data` + `docker compose up --build` → Grafana at :3001 works with provisioned datasources/dashboard, no manual clicks |
| No test regressions | Full reactor `mvn clean verify` after every pom/logback change |
| CI unaffected | The existing GitHub Actions run stays green (observability containers are compose-only; tests don't need them) |
