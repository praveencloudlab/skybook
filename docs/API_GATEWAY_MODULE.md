# 🚪 SkyBook API Gateway Module — Design

---

## Project Information

| | |
|---|---|
| **Module** | `api-gateway` |
| **Branch** | `feature/api-gateway` |
| **Base package** | `com.skybook.praveen.apigateway` |
| **Port** | `8080` (freed up by giving auth-service an explicit port — see the load-bearing finding below) |
| **Database** | None — stateless routing layer, no persistence |
| **Status** | Design complete. Implementation starting per §9's build order. |

Single public entry point for every SkyBook service. Today, a client (Postman, a future web/mobile front end) has to know 6 different ports and there is no consistent security, CORS, or logging story across them. The gateway collapses that to one host:port, one place where JWT is actually enforced, and one place to add cross-cutting concerns without touching seven codebases.

**Load-bearing finding from this doc's research pass, not from the original brief:** `auth-service` has no `server.port` in its `application.yml` at all, so it silently defaults to Spring Boot's `8080` (`backend/auth-service/src/main/resources/application.yml`). That is exactly the port a gateway conventionally owns. **Decision (confirmed): auth-service gets an explicit port, `8081`, filling the gap in the existing 8082–8087 sequence; the gateway takes `8080`.** This is a one-line, additive change to auth-service's `application.yml`, done as part of this branch.

**Second load-bearing finding: JWT is not actually enforced anywhere downstream today.** `auth-service` issues tokens and validates them for its own endpoints (`JwtAuthenticationFilter`), but every other service's `SecurityConfig` is `anyRequest().permitAll()` — booking-service's even says so in a comment (`backend/booking-service/.../config/SecurityConfig.java`). So right now, anyone who can reach a service's port directly can call it with no token at all. **Decision: the gateway becomes the actual JWT enforcement boundary for v1.** Downstream services keep `permitAll()` for now (they trust the network path, i.e. "only the gateway can reach these ports" — true in the current all-localhost dev setup, and the deployment model this implies is captured in §8). Pushing JWT validation into every downstream service too (defense in depth) is real work — flagged in §7 as deferred, not done here, so this branch doesn't balloon into re-touching seven `SecurityConfig` files.

---

# Table of Contents

1. [Overview](#1-overview)
2. [Architecture](#2-architecture)
3. [Routing Table](#3-routing-table)
4. [JWT Validation](#4-jwt-validation)
5. [CORS](#5-cors)
6. [Rate Limiting](#6-rate-limiting)
7. [Request Logging](#7-request-logging)
8. [Deployment Model & Trust Boundary](#8-deployment-model--trust-boundary)
9. [Configuration](#9-configuration)
10. [Deferred / Out of Scope](#10-deferred--out-of-scope)
11. [Known Risks / Open Questions](#11-known-risks--open-questions)
12. [Build Order](#12-build-order)
13. [Testing Plan](#13-testing-plan)

---

# 1. Overview

`api-gateway` is a new Spring Boot module that sits in front of all seven backend services and owns:

- **Routing** — one public host:port, path-based routing to the right downstream service.
- **JWT validation** — verifies the signature/expiry of tokens issued by auth-service; rejects unauthenticated requests to protected routes before they ever reach a business service.
- **CORS** — one place to configure allowed origins/methods/headers, instead of nowhere (today: nowhere).
- **Rate limiting** — per-client-IP request throttling; first place this exists in the codebase at all.
- **Request logging** — a single structured log line per request (method, path, status, latency, a correlation id), the beginning of real observability.

What it deliberately does **not** own:

- Business logic of any kind — pure routing/cross-cutting-concerns layer.
- Token issuance — still auth-service's job (`POST /api/auth/login`, `/register`).
- Service discovery — no Eureka/Consul exists in this stack (confirmed: no such dependency anywhere in `backend/`), so routes are static `host:port` URIs, same pattern every Feign client already uses (`flight-service.base-url: http://localhost:8082` style keys in each service's `application.yml`). Revisit if/when services move off fixed localhost ports (e.g. containerized with per-container hostnames — see `feature/dockerization`).
- Circuit breaking / retries — no resilience4j anywhere in the codebase today (confirmed via search); noted as a natural fast-follow once the gateway exists, not built here.

---

# 2. Architecture

```
Client (Postman / future web app)
        │
        ▼
┌───────────────────────────────────────────────┐
│               api-gateway  :8080               │
│                                                 │
│  RequestLoggingFilter  (every request)         │
│         │                                      │
│  CorsConfig            (preflight + headers)   │
│         │                                      │
│  RateLimitFilter       (per client IP)         │
│         │                                      │
│  JwtAuthenticationFilter  (protected routes)   │
│         │                                      │
│  Route matcher (path prefix → downstream URI)  │
└───────────────────────────────────────────────┘
        │
        ├──/api/auth/**          → auth-service         :8081
        ├──/api/flights/**       → flight-service        :8082
        ├──/api/flight-schedules/** → flight-service      :8082
        ├──/api/bookings/**      → booking-service        :8083
        ├──/api/reservations/**  → inventory-service      :8084
        ├──/api/inventory/**     → inventory-service      :8084
        ├──/api/aircraft/**      → inventory-service      :8084
        ├──/api/payments/**      → payment-service         :8086
        ├──/api/refunds/**       → payment-service         :8086
        ├──/api/invoices/**      → payment-service         :8086
        ├──/api/checkins/**      → checkin-service          :8087
        ├──/api/boarding-passes/**→ checkin-service          :8087
        ├──/api/baggage/**       → checkin-service          :8087
        └──/api/manifests/**     → checkin-service          :8087

notification-service (:8085) is not routed — it has no REST controllers
(confirmed: no @RequestMapping anywhere in its source), it's Kafka-consumer-only.
```

**Framework choice: Spring Cloud Gateway Server *WebMVC*, not the classic reactive Gateway.**

Every other SkyBook service is a traditional Spring MVC app on Tomcat, blocking JDBC via Hibernate — nothing in this codebase is reactive. The classic `spring-cloud-starter-gateway` is built on WebFlux/Netty/Reactor; mixing that into an otherwise all-servlet fleet would mean a different threading model, different debugging story, and a real footgun (blocking calls inside a Netty event loop) for no benefit here at this scale. `spring-cloud-starter-gateway-server-webmvc` (GA as of the Spring Cloud 2024.0.x/2025.0.x train, which this repo already pins via its existing `spring-cloud-dependencies` BOM import in `backend/pom.xml`) gives the same routing/predicate/filter model on the plain Servlet stack. That's the one this module uses.

---

# 3. Routing Table

| Path prefix | Downstream service | Port | Auth required |
|---|---|---|---|
| `/api/auth/register`, `/api/auth/login` | auth-service | 8081 | No (public) |
| `/api/auth/**` (anything else under it, if added later) | auth-service | 8081 | Yes |
| `/api/flights/**` | flight-service | 8082 | Yes |
| `/api/flight-schedules/**` | flight-service | 8082 | Yes |
| `/api/bookings/**` | booking-service | 8083 | Yes |
| `/api/reservations/**` | inventory-service | 8084 | Yes |
| `/api/inventory/**` | inventory-service | 8084 | Yes |
| `/api/aircraft/**` | inventory-service | 8084 | Yes |
| `/api/payments/**` | payment-service | 8086 | Yes |
| `/api/refunds/**` | payment-service | 8086 | Yes |
| `/api/invoices/**` | payment-service | 8086 | Yes |
| `/api/checkins/**` | checkin-service | 8087 | Yes |
| `/api/boarding-passes/**` | checkin-service | 8087 | Yes |
| `/api/baggage/**` | checkin-service | 8087 | Yes |
| `/api/manifests/**` | checkin-service | 8087 | Yes |
| `/actuator/**` (gateway's own) | api-gateway itself | 8080 | No (public) |

Per-service `application.yml` files each define a `services.<name>.base-url` key (mirroring the `flight-service.base-url` / `inventory-service.base-url` keys checkin-service already uses for its Feign clients), so route targets are configurable per environment without touching Java code.

---

# 4. JWT Validation

- **Verifies, doesn't reissue.** The gateway never mints tokens — that stays auth-service's job. The gateway only checks that a token presented in `Authorization: Bearer <token>` is a validly-signed, non-expired token from auth-service.
- **Shared secret.** auth-service signs with HMAC using `jwt.secret` (env var `JWT_SECRET`, `JwtProperties.java`). The gateway needs the **same** `JWT_SECRET` value to verify the signature — this is the one piece of config that must be kept in sync across the two services/`. env` files. Documented explicitly in §9 rather than assumed.
- **Claims today are minimal.** auth-service's `JwtService.generateToken(String email)` only puts `subject` (email) + `issuedAt` + `expiration` into the token — no roles, no userId claim (confirmed by reading `JwtService.java`). So v1 of gateway-side validation can only assert "this is a token auth-service issued and it hasn't expired" — it cannot do role-based route authorization yet, because there's no role claim to check. That's a real gap, not a gateway limitation; flagged in §11.
- **On success:** the gateway forwards the request to the downstream service with the validated subject attached as a header, `X-Auth-User: <email>`, so a downstream service can trust it without re-validating the token itself (though today none of them read it — they're all `permitAll()`, see the second load-bearing finding above). This header is the seam a future "defense in depth" pass would build on.
- **On failure** (missing header, malformed token, bad signature, expired): gateway returns `401` itself, in the same `ErrorResponse` shape `skybook-common` already defines (`com.skybook.praveen.common.exception.ErrorResponse` — reused rather than inventing a second error envelope), and the request never reaches a downstream service.
- **Public routes bypass the filter entirely** — `/api/auth/register`, `/api/auth/login`, and the gateway's own `/actuator/**`. Everything else in the routing table requires a valid token.

---

# 5. CORS

Nothing in the codebase configures CORS today (confirmed: no match for "cors" anywhere under any service's `src/main`). The gateway becomes the **only** place CORS is configured — downstream services don't need their own CORS config once nothing but the gateway calls them directly in the intended deployment model (§8).

- Allowed origins: configurable list (`gateway.cors.allowed-origins` in `application.yml`), defaulting to `http://localhost:5173`/`http://localhost:3000` (common Vite/CRA dev ports) for local frontend development against this gateway — placeholder until `frontend/` (currently an empty `.gitkeep` placeholder in the repo) actually exists.
- Allowed methods: `GET, POST, PUT, PATCH, DELETE, OPTIONS`.
- Allowed headers: `Authorization, Content-Type`.
- Credentials: not allowed (no cookie-based auth anywhere in this system — it's all bearer tokens), so `allow-credentials: false` and origins do not need to be individually enumerated for a wildcard-with-credentials workaround.

---

# 6. Rate Limiting

No rate-limiting library exists anywhere in this codebase yet (confirmed: no `resilience4j`, `bucket4j`, or hand-rolled `RateLimiter` anywhere in `backend/`), and there's no Redis instance in the current stack either (the roadmap's Phase 2 Search Service is where Redis first shows up). Pulling in Redis just to rate-limit would be a disproportionate new piece of infra for this branch.

**Decision: an in-memory, per-client-IP token bucket, implemented as a small custom `HandlerFilterFunction`.** Deliberately not Spring Cloud Gateway's built-in `RequestRateLimiter` filter, because that filter is backed by Redis (`RedisRateLimiter`) in both the reactive and WebMVC flavors — there's no in-memory implementation shipped out of the box. A simple `ConcurrentHashMap<String clientIp, TokenBucket>` with a scheduled cleanup of stale entries is sufficient for a single-instance gateway (which is what this is — no horizontal scaling of the gateway itself is in scope). Documented as a known limitation if the gateway is ever scaled to multiple instances (§11) — at that point it needs to move to Redis so limits are shared across instances, same as the built-in filter would require anyway.

- Default limit: configurable, starting at 100 requests / minute per client IP (`gateway.rate-limit.requests-per-minute`).
- Exceeding the limit returns `429 Too Many Requests` with a `Retry-After` header.
- Scoped per-IP globally (not per-route) for v1 — simplest useful thing; per-route limits are a config extension, not an architecture change, if needed later.

---

# 7. Request Logging

A single `HandlerFilterFunction` wraps every routed request and logs one structured line on completion:

```
method, path, downstream target, response status, latency (ms), correlation id
```

- **Correlation id**: if the client sends `X-Correlation-Id`, it's reused; otherwise the gateway generates a UUID. Either way it's forwarded downstream as `X-Correlation-Id` so a future centralized-logging pass (Phase 1's `feature/observability`) can stitch a request's path across services. No downstream service reads this today — it's a forward-compatible seam, not a full trace, since there's no OpenTelemetry/tracing library anywhere in the codebase yet either.
- This is deliberately just structured `log.info(...)` output for now, not a metrics/tracing system — that's `feature/observability`'s job (Prometheus/Grafana/Loki per the roadmap). The gateway's contribution here is making sure every request produces *one* consistent log line in *one* place, which today doesn't exist anywhere in the fleet.

---

# 8. Deployment Model & Trust Boundary

This section exists because §4 and §5's decisions (gateway-only JWT enforcement, gateway-only CORS, downstream services staying `permitAll()`) only make sense under one assumption, so it's written down rather than left implicit:

**Assumption: only the gateway is reachable from outside the local machine/private network; the seven backend services are reachable only from the gateway.** In today's all-`localhost` dev setup this is trivially true (nothing stops a client hitting `localhost:8083` directly, but nothing is exposed to the internet either). It becomes a real requirement once this moves to `feature/dockerization` / a real deployment — the backend services must land on an internal Docker network / internal Kubernetes Service, **not** get individual public ports or a public LoadBalancer, or the entire JWT-enforcement story in §4 is bypassable by just calling a service directly. This constraint is the single most important thing for `feature/dockerization` to preserve, and is called out there for that reason.

---

# 9. Configuration

`backend/api-gateway/src/main/resources/application.yml` (shape, not final):

```yaml
server:
  port: 8080

services:
  auth-service:
    base-url: http://localhost:8081
  flight-service:
    base-url: http://localhost:8082
  booking-service:
    base-url: http://localhost:8083
  inventory-service:
    base-url: http://localhost:8084
  payment-service:
    base-url: http://localhost:8086
  checkin-service:
    base-url: http://localhost:8087

jwt:
  secret: ${JWT_SECRET}   # MUST match auth-service's jwt.secret exactly

gateway:
  cors:
    allowed-origins: http://localhost:5173,http://localhost:3000
  rate-limit:
    requests-per-minute: 100

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

`auth-service/src/main/resources/application.yml` gets one additive line as part of this branch:

```yaml
server:
  port: 8081
```

`backend/pom.xml` gets one new `<module>api-gateway</module>` entry alongside the existing eight.

---

# 10. Deferred / Out of Scope

- **Role-based authorization at the gateway** — blocked on auth-service's JWT not carrying a roles/userId claim yet. Adding that claim is auth-service's change, not the gateway's; once it exists, the gateway's JWT filter can read it and do route-level role checks.
- **Defense-in-depth JWT validation in each downstream service** — real work across seven `SecurityConfig` classes, deliberately not bundled into this branch (see the second load-bearing finding). Tracked as a fast-follow once the gateway's trust-boundary model (§8) is actually enforced at the network level (post-`feature/dockerization`).
- **Redis-backed distributed rate limiting** — only needed if the gateway itself is horizontally scaled; not in scope while it's a single instance.
- **Circuit breakers / retries / timeouts per downstream call** — no resilience4j anywhere yet; natural next step once the gateway exists, not bundled here to keep this branch's diff reviewable.
- **Service discovery (Eureka/Consul)** — static `base-url` config is consistent with how every Feign client in the fleet already resolves its dependencies; revisit only if `feature/dockerization` makes fixed `localhost:PORT` unworkable.
- **Request/response body transformation, GraphQL federation, API versioning strategy** — none of these are needed by anything that exists in SkyBook today.

---

# 11. Known Risks / Open Questions

- **`JWT_SECRET` drift.** The gateway and auth-service must be started with the identical `JWT_SECRET` env var, or every token the gateway validates will look forged (or vice versa: a real auth-service outage could make one appear to be a stale secret). No shared-secret-store exists in this project yet (no Vault/K8s Secrets in scope) — this is a manual-discipline risk, called out explicitly rather than silently assumed to be fine.
- **Single point of failure.** Once frontends only ever talk to the gateway, the gateway becomes a hard dependency for the entire system where none existed before (clients could previously hit any single service directly). Acceptable trade-off for the stated goal (one entry point, real JWT enforcement, real CORS) but worth stating plainly — it's the standard gateway trade-off, not an oversight.
- **In-memory rate limiter resets on restart** — a client that was throttled loses that state the moment the gateway restarts. Fine for a dev/portfolio project; would need Redis-backing before this matters in a real multi-instance deployment (§6).
- **Static routing table duplicates each service's path prefix in two places** (the service's own `@RequestMapping` and the gateway's route config) — if a service adds a new top-level path prefix, the gateway's `application.yml` needs a matching update or that path 404s at the gateway before ever reaching the service. No automatic discovery of new prefixes exists (and wouldn't, without a service-discovery layer that's explicitly out of scope — §10).

---

# 12. Build Order

1. **Bootstrap the module** — `api-gateway/pom.xml` (parent = `skybook-backend`, dependencies: `spring-cloud-starter-gateway-server-webmvc`, `spring-boot-starter-actuator`, `skybook-common` for `ErrorResponse`, `jjwt-api`/`jjwt-impl`/`jjwt-jackson` matching whatever auth-service uses for JWT so verification logic is compatible), `ApiGatewayApplication.java`, add `<module>api-gateway</module>` to `backend/pom.xml`. Verify `mvn -pl api-gateway -am compile` succeeds before anything else.
2. **auth-service port fix** — add `server.port: 8081` to `auth-service/application.yml`. One line, verify auth-service still starts on 8081.
3. **Static routing, no auth yet** — wire up the routing table from §3 as plain pass-through routes (`RouterFunction`/YAML, whichever the resolved `spring-cloud-starter-gateway-server-webmvc` version actually supports — confirm empirically here rather than assuming, since this module is new to the fleet). Verify manually: gateway on 8080 successfully proxies a request to at least two different downstream services.
4. **Request logging filter** — add first since it makes every subsequent step's manual testing easier to observe.
5. **JWT validation filter** — §4. Verify: public routes work with no token; protected routes 401 with no/bad token and succeed with a real auth-service-issued token.
6. **CORS config** — §5.
7. **Rate limiting filter** — §6. Verify: N+1th request within a minute from the same IP gets 429.
8. **Error handling** — consistent `ErrorResponse` body for gateway-originated 401/404/429/502 (downstream unreachable) responses, matching `skybook-common`'s existing shape.
9. **Tests** — per §13.
10. **Update this doc's status and add an Implementation Notes section** documenting anything that changed from the design during the build (matching the pattern every other module doc in this repo follows).

---

# 13. Testing Plan

| Layer | What's tested | Tooling |
|---|---|---|
| Routing | Each path prefix in §3 resolves to the correct downstream `base-url` | `@SpringBootTest(webEnvironment=RANDOM_PORT)` + `WireMock` (or a trivial stub `@RestController` on a random port standing in for "a downstream service") |
| JWT filter | Valid token → forwarded with `X-Auth-User`; missing/expired/malformed token → 401; public routes bypass the filter | Unit test against the filter directly, constructing tokens with the same `jjwt` APIs auth-service uses |
| CORS | Preflight `OPTIONS` gets the configured headers; disallowed origin is rejected | `MockMvc`/`RestTemplate` against a running test instance |
| Rate limiter | Nth request under the limit passes; N+1th returns 429; counter resets after the window | Unit test against the token-bucket class directly (no need to spin up the whole app) |
| Logging filter | One log line per request, correlation id generated when absent and passed through when present | Unit test asserting on the filter's behavior, not log output scraping |
| End-to-end | A full JWT-protected round trip: login via gateway → `/api/auth/login` → token → call a protected route via the gateway → 200 from the real downstream service | Manual verification against the live services already running locally (same style used throughout this project's other modules), documented in Implementation Notes once run |

No JPA/Testcontainers layer — this module has no database. No Kafka layer — this module doesn't touch Kafka either (it's pure HTTP routing).
