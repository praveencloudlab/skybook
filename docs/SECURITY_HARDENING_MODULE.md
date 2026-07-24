# 🔒 SkyBook Security Hardening — Design

---

## Project Information

| | |
|---|---|
| **Scope** | Close the fleet's real security gaps: every service authenticates requests (not just the gateway), role-based authorization, hardened JWT issuance/validation, input validation + safe error handling on the auth surface, non-root/read-only containers, network isolation of internal ports, and supply-chain scanning in CI |
| **Branch** | `feature/security-hardening` |
| **Status** | **✅ Implemented and verified** — all 14 build-order steps (§13) complete on `feature/security-hardening`. Every service validates the JWT itself (RS256, verify-only public key), enforces the §4.4 role + OWNER + SERVICE matrix, and the auth surface is hardened (validation, typed errors, constant-time login). Internal ports (services + Postgres + Kafka + management) are unpublished; actuator lives on an internal-only management port; containers run non-root on a read-only rootfs; committed secret defaults are gone (fail-fast); CI runs Trivy dependency + image scans with scan-before-push. Live-certified 23/23 on the running fleet (see §16). Design frozen at 10/10 after review 5; see §16 for the handful of implementation-time deviations. |
| **Revision history** | R1 (8.3): build-order-before-propagation, `ROLE_SERVICE` identity, auto-config module, ownership in scope, HS384/48-byte, port isolation. R2 (8.8): RS256 + per-service credentials, per-operation Feign identity, two-rule audience, ownership→Payment/CheckIn, management port, email-collision migration, TTL 60/10. R3 (9.5): Feign identity frozen at client-interface level, `/service-token` de-routed + client-credential chain, inventory→flight identity, token↔role coherence. R4 (9.5→9.8): build order reordered, Feign/filter registration isolation, security-persistence migrations. R5 (10/10, FROZEN): atomic issuer/verifier switch, `SecurityContext`-before-capture ordering, client-cred env + risk cleanups. |

Goal: take SkyBook from "secured at the front door only" to **defense in depth**.
Today a valid JWT is checked in exactly one place — the API gateway — and every
service behind it trusts anything that reaches its port. That is a real,
demonstrable bypass, not a theoretical one (§2.1). This module makes each
service enforce identity and authority itself, hardens how tokens are minted and
verified, fixes the unauthenticated auth surface, and adds container + CI
supply-chain hardening — without breaking the working end-to-end flows the
previous six branches built.

---

# 1. Overview

Six things, in priority order (the build order in §12 follows this):

1. **Service-to-service trust** — downstream services stop trusting the network
   and start validating the JWT themselves; internal ports stop being published.
2. **Authorization (roles)** — identity gains roles; admin-only surfaces
   (aircraft/seat-map/inventory/flight creation) are enforced, not open to any
   logged-in user.
3. **JWT hardening** — issuer/audience claims, boot-time secret-strength
   enforcement, shorter access-token TTL, and a documented key story.
4. **Auth surface hardening** — request validation, password policy, and safe
   error handling (no `RuntimeException`-as-500, no user-enumeration).
5. **Container hardening** — non-root user, read-only root filesystem, dropped
   capabilities.
6. **Supply-chain scanning** — dependency + image scanning in CI, wired to fail
   the build on high/critical findings.

Each is independently shippable and independently verifiable. If review wants to
cut scope, the natural cut line is after item 4 (the application-layer work) with
5–6 (infra/CI) deferred — but I recommend keeping all six since 5–6 are small.

---

# 2. Load-Bearing Findings (traced against the live code, not assumed)

## 2.1 The gateway is the *only* enforcement point — and it is bypassable

`api-gateway`'s `JwtAuthenticationFilter` is "the ONE place in the whole fleet
that currently rejects an unauthenticated request" (its own Javadoc says so).
Every downstream service's `SecurityConfig` ends in **`.anyRequest().permitAll()`**:

- `booking-service`, `inventory-service`, `payment-service`, `checkin-service`
  each have a `SecurityConfig` that disables CSRF and permits everything.
- `flight-service` and `notification-service` have **no Spring Security on the
  classpath at all** — no filter chain, fully open.

And `docker-compose.yml` **publishes every internal port** (`8081`–`8087`) onto
the host. So:

```
curl http://localhost:8083/api/bookings ...        # bypasses the gateway entirely
curl -H "X-Auth-User: admin@skybook.com" ...        # forges the gateway's trust header
```

both succeed today. The gateway sets an `X-Auth-User` header after validating the
token, but **no downstream service reads it**, and any direct caller can set it
themselves. This is the single most important gap; §3 fixes it two ways
(network + application layer).

## 2.2 There is no authorization, only authentication

No `role`, `authority`, `ROLE_`, `hasRole`, or `hasAuthority` exists anywhere in
`backend/` (grepped). The `User` entity has `id`, `fullName`, `email`,
`password` — no role. The JWT carries only `subject` (the email). So a freshly
registered user can call **every** endpoint the same as anyone else: create
aircraft, define seat maps, create flight inventory, cancel any booking. The
`"role":"ADMIN"` field callers can pass to `/register` is silently ignored
(`RegisterRequest` has only `fullName`/`email`/`password`).

## 2.3 JWT issuance is minimal and the secret has a weak default

`JwtService.generateToken` sets `subject` + `issuedAt` + `expiration` only — no
issuer, no audience, no roles. The signing key is
`Keys.hmacShaKeyFor(secret.getBytes(UTF_8))` from `${JWT_SECRET}`, whose
`env.example` default is literally `change-me-to-a-long-random-string`. jjwt
requires ≥256 bits for HS256; there is **no boot-time check** that the deployed
secret is long/random enough — a short secret would either throw lazily on first
sign or (worse) sign weakly. TTL is `3600000` ms (1 h); there are no refresh
tokens (acceptable for v1 — see §5).

## 2.4 The auth surface has no validation and leaks errors as 500s

`RegisterRequest` and `LoginRequest` are bare records with **zero** Bean
Validation. `AuthService.register` throws `new RuntimeException("Email already
registered")` and `login` throws `RuntimeException("Invalid email or password")`;
`auth-service` has **no `@RestControllerAdvice`**, so both surface as HTTP **500**
with a stack-traceable message instead of `409`/`401`. There is a commented-out
plaintext-password line in `register` (`// user.setPassword(request.password())`)
— dead but worth removing so no one ever uncomments it. Passwords themselves are
correctly BCrypt-hashed (good — not changing that).

## 2.5 Actuator is fully open on published ports

Every service permits `/actuator/**` and exposes
`health,info,metrics,prometheus,circuitbreakers`. With internal ports published
(§2.1), `curl localhost:8083/actuator/prometheus` is unauthenticated from the
host. Once §3 stops publishing those ports this is much less exposed, but the
management surface should still be tightened (§7).

## 2.6 Containers run as root; CI does no scanning

The Dockerfile template ends with `FROM eclipse-temurin:21-jre-jammy` +
`ENTRYPOINT java -jar app.jar` and **no `USER`** — every container runs as root
with a writable root filesystem. The CI workflow has **zero** supply-chain
scanning (no Trivy/OWASP/Snyk/grype grep hits). These are the cheapest wins in
the module (§8, §9).

## 2.7 What is already good (do not regress)

- **BCrypt** password hashing (`PasswordConfig`).
- **Gateway rate limiting** — `RateLimitFilter` (per-IP fixed window) runs
  *before* JWT validation, so it already shields the public login/register
  endpoints. Kept as-is.
- **CORS** locked to a configured `allowedOrigins` list (empty by default).
- **`.env` is gitignored**; `JWT_SECRET`/DB creds come from env, not committed.
- The gateway JWT filter correctly bypasses CORS preflight and public paths.

---

# 3. Service-to-Service Trust (the core fix)

**Decision: defense in depth — network isolation AND per-service JWT validation.**
Either alone is insufficient; both together is the standard posture.

## 3.1 Network layer — stop publishing internal ports

In `docker-compose.yml`, internal services (`auth`, `flight`, `booking`,
`inventory`, `payment`, `checkin`, `notification`) drop their `ports:` host
mappings and keep only `expose:`. **Postgres (`5432`) and Kafka (`9092`) lose
their host mappings too** (review 1, correction #6) — today `5432` is published
with credentials `postgres/postgres`, so the whole database is reachable from the
host with a trivial password. The committed compose publishes **only**:

```
api-gateway        (8080)   — the sole application ingress
Grafana            (3000)   — the ops UI
Prometheus/Tempo/Loki       — optional, local-dev observability only
```

Everything else — all 7 services, Postgres, Kafka, and every service's
management port (§7) — is `expose:`-only on the compose network. This finally
matches the Kubernetes model already designed (ClusterIP + one ingress).

> Debuggability note: a gitignored `docker-compose.override.yml` can re-publish
> any port (including 5432/9092) for local debugging. The committed compose is
> secure-by-default.

## 3.2 Application layer — every service validates the JWT

The gateway keeps validating (fail fast, good errors). **Additionally**, each
downstream service validates the same token itself, so a caller who reaches the
port some other way still gets rejected.

**`skybook-security` is a Spring Boot auto-configuration module** (review 1,
correction #3), not a bag of shared classes. Classes under
`com.skybook.praveen.security` are *not* component-scanned by an app rooted at
`com.skybook.praveen.bookingservice`, so the infrastructure is wired via an
auto-configuration registered in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
— every service gets it from the single dependency, no per-service `@ComponentScan`
or `@Import`. Module contents:

```
skybook-security
├── JwtTokenValidator                 # verify signature+alg+iss+aud+exp+iat+role
├── JwtAuthenticationFilter           # populates the SecurityContext from the token
├── JwtSecurityProperties             # public key, issuer, user/service audiences, enforcement flag
├── JsonAuthenticationEntryPoint      # 401 as the fleet ErrorResponse shape
├── JsonAccessDeniedHandler           # 403 as the fleet ErrorResponse shape
├── BearerTokenFeignInterceptor       # §3.3 token propagation
├── ServiceTokenProvider              # §3.3 ROLE_SERVICE token fetch/cache/refresh
└── JwtSecurityAutoConfiguration      # ties the above together; registered via imports
```

- **The shared module provides authentication infrastructure; each service keeps
  its own local `SecurityConfig`** — the URL/role matrix differs per service
  (§4), so the chain composition stays local while the filter/validator/handlers
  are shared. Every local chain must include, explicitly:

  ```java
  .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
  .formLogin(AbstractHttpConfigurer::disable)
  .httpBasic(AbstractHttpConfigurer::disable)
  .csrf(AbstractHttpConfigurer::disable)
  .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()   // preflight, like the gateway
  ```

  Without the `OPTIONS` rule, downstream authentication breaks browser preflight
  even though the gateway already bypasses it (the request reaches the service on
  its own once ports are internal-only).
- Each service's chain: `permitAll()` on `/actuator/health/**` and OPTIONS →
  **`.anyRequest().authenticated()`** plus the §4 role rules.
- `flight-service` and `notification-service` gain `spring-boot-starter-security`
  + the module (they have none today).
- **The gateway migrates onto the shared `JwtTokenValidator`.** Keeping
  `GatewayJwtValidator` separate after a shared validator exists would create two
  implementations that could drift on issuer/audience/algorithm/role rules — the
  duplication was only ever justified because no other service needed jjwt. The
  gateway's *filter* (public-path bypass, `X-Auth-User` enrichment) stays; only
  the verify-and-extract core is replaced by the shared validator.
- **`X-Auth-User` is no longer trusted as an identity claim.** Identity comes
  from the verified token. The gateway may still set the header for
  logging/tracing, but authorization never reads it.
- **Rollout safety flag** — `skybook.security.enforcement-enabled` gates the
  `authenticated()` switch during rollout so a chain can be built and merged
  before it is turned on. It **defaults to `true`** everywhere except the test
  profile; it exists to sequence the rollout in §12, not to ship disabled.

## 3.3 Internal traffic that is not a user request

Kafka consumers (payment, notification, booking, checkin) and inter-service
Feign calls (booking→inventory, checkin→inventory, booking→flight) must keep
working. Two distinct identity paths, chosen explicitly per call site — **never
by "is there a request context lying around?"** (review 1, correction #2):

- **Kafka** — not HTTP, unaffected by the filter chains.
- **User-originated read** — the `BearerTokenFeignInterceptor` forwards the
  **exact incoming bearer token** on the outbound Feign call (e.g. booking →
  flight read), so the downstream service authenticates the real end user.
- **Internal write** — the caller uses **its own `ROLE_SERVICE` token** (e.g.
  booking → inventory hold/release/reserve, check-in → inventory reserve/cancel),
  because the matrix (§4.4) denies USER access to inventory mutations (see the
  contradiction fix below).
- **Kafka/scheduler origin** (no user token exists): the caller uses its
  `ROLE_SERVICE` token — event-driven finalize, stale-draft/hold sweeps.
- **inventory → flight** (review 3, blocker #3): inventory-service already calls
  `GET /api/flights/{id}` (verified — its only downstream). Once flight-service
  authenticates, this call needs an identity too. It runs inside the
  **ADMIN-originated** inventory-creation flow, so it **propagates the ADMIN
  token** (`getFlightAsUser`); if it is ever reused from a scheduler/Kafka origin
  it takes an `inventory-service → flight-service` service token
  (`getFlightAsService`). Without this, aircraft/inventory administration would
  fail with an internal 401 even while the passenger booking E2E stays green — so
  it is verified **before** flight-service flips to `authenticated()` (§13).

Identity is chosen **explicitly per Feign operation**, never by "is a request
context lying around?".

### Asymmetric signing — RS256, not a shared HMAC secret (review 2, blocker #1)

The R1 claim that "holding `JWT_SECRET` cannot obtain `ROLE_SERVICE`" was
**wrong**: HS384 is symmetric, so any service holding the shared secret can
locally forge and sign `{"roles":["ROLE_SERVICE"]}` (or `ROLE_ADMIN`) without ever
calling auth-service. The separate service-registration secret only guards the
*endpoint*, not local forgery. So v1 moves to asymmetric signing:

```
auth-service   → owns the PRIVATE signing key (the only minter of any token)
gateway + all  → receive the PUBLIC verification key only
downstream       → can VERIFY tokens, can mint NONE (user, admin, or service)
```

- **RS256 with a ≥2048-bit key** (Ed25519 also acceptable on Java 21; jjwt
  supports both). This supersedes the HS384 decision throughout §5.
- The public key is distributed as config (`JWT_PUBLIC_KEY`, PEM); the private
  key (`JWT_PRIVATE_KEY`) exists **only** in auth-service's environment.
- **Now the service-token endpoint is meaningful**, because it is the *only* way
  to obtain a `ROLE_SERVICE` token.

### Per-service client credentials (review 2, blocker #1)

Replace the single `SKYBOOK_SERVICE_AUTH_SECRET` with **per-service client
credentials** so one compromised service cannot impersonate another:

```
booking-service   → client-id + secret
checkin-service   → client-id + secret
payment-service   → client-id + secret
```

- `POST /api/auth/service-token` (internal, **not** gateway-routed) authenticates
  the caller by its client credential and issues a short-lived `ROLE_SERVICE`
  token. **`sub` is derived from the authenticated client credential, never from
  a caller-supplied service name.**
- Auth-service enforces a **caller → audience allowlist** and issues a token only
  for an audience that caller is allowed to target:

  ```
  booking-service   → { flight-service, inventory-service }
  checkin-service   → { flight-service, inventory-service }
  payment-service   → { booking-service }
  inventory-service → { flight-service }        # inventory→flight, review 3 blocker #3
  ```

### Isolating and authenticating the token endpoint (review 3, blocker #2)

Two concrete exposures must close, or the endpoint is reachable and/or
un-authenticable:

1. **De-route it from the gateway.** The gateway currently forwards the wildcard
   `path("/api/auth/**")` to auth-service (verified) — so adding
   `/api/auth/service-token` would publish it on `:8080`. The gateway route
   **narrows to exactly `/api/auth/register` and `/api/auth/login`**; everything
   else under `/api/auth/**` (incl. `/service-token`) is no longer routed and is
   reachable only service-to-service on the internal network.
2. **A separate credential-authenticated filter chain** — the caller is
   obtaining its *first* SERVICE token and therefore cannot already present
   `ROLE_SERVICE`, so this endpoint is **not** JWT-authenticated. Spring Security
   runs only the first matching `SecurityFilterChain`, so ordering + matcher are
   deliberate:

   ```java
   @Order(1)
   securityMatcher("/api/auth/service-token")   // client-credential chain, NO JWT
     → authenticate per-service client credentials → issue ROLE_SERVICE token

   @Order(2)
   // register/login (public) + any future JWT-guarded auth admin
   ```

- **Credential storage.** Each caller service holds a **high-entropy plaintext
  client secret** (its own env); auth-service stores a **BCrypt/Argon2 hash per
  client id**, verified in constant time. The **client id determines `sub` and
  the allowlist** — the request body is never trusted for identity. Service
  secrets are *not* all kept as plaintext env vars in auth-service.
- In the matrix, this endpoint is labelled **`CLIENT_CREDENTIAL`**, not
  `SERVICE`.

- `ServiceTokenProvider` (in `skybook-security`) fetches per target audience on
  first use, **caches, and refreshes shortly before expiry** — never per call,
  never stale.
- Service token shape:

  ```json
  {
    "sub": "booking-service",
    "token_type": "service",
    "roles": ["ROLE_SERVICE"],
    "iss": "${JWT_ISSUER}",
    "aud": "inventory-service",
    "exp": "<~10 min>"
  }
  ```

- **Fail closed.** Only explicitly identified internal operations elevate to
  `ROLE_SERVICE`; any call site with neither a propagated user token nor an
  explicit service-token request is **rejected**, never silently elevated.

### Why not propagate the USER token to inventory writes (review 2, blocker #2)

Propagating the user token to `POST /inventory/hold` would **403** — the matrix
denies USER every inventory mutation; only SERVICE may call them. For v1
inventory does not need the user's identity to mutate a seat: booking-service and
check-in-service already authenticate *and* authorize the user (ownership, §4.2)
**before** making the internal call. So the internal write uses the caller's
`ROLE_SERVICE` token. When downstream audit attribution is genuinely needed
later, add an on-behalf-of token carrying an **`act` claim** (RFC 8693) rather
than letting USER tokens onto internal inventory writes — noted as a §14
follow-up.

### Feign identity is frozen at the client-interface level, not by a global interceptor (review 3, blocker #1)

The danger: booking's **current single** `InventoryServiceFeignClient` mixes a
read (`getCabins()`) with five writes (`holdSeat`, `autoHoldSeat`, `releaseHold`,
`reserveSeat`, `cancelReservation`). A globally-registered
`BearerTokenFeignInterceptor` would forward the USER token to **every** method —
including the writes the matrix denies to USER — producing the exact 403 this
whole design is meant to avoid. So identity is bound to **separate client
interfaces**, each with its own `contextId` and its own Feign configuration:

```
InventoryQueryFeignClient    (contextId = "inventoryQuery")
  → @Configuration UserTokenFeignConfiguration
  → propagates the incoming USER/ADMIN bearer token
  → getCabins(...)                             [read]

InventoryCommandFeignClient  (contextId = "inventoryCommand")
  → @Configuration ServiceTokenFeignConfiguration(audience = "inventory-service")
  → attaches a ROLE_SERVICE token, aud = inventory-service
  → holdSeat / autoHoldSeat / releaseHold / reserveSeat / cancelReservation  [writes]

ServiceTokenClient           (contextId = "authServiceToken")
  → isolated config; uses client-credentials only
  → inherits NEITHER interceptor (or it would loop / attach the wrong identity)
```

Spring Cloud OpenFeign supports per-client configuration and multiple clients
against the same target via distinct `contextId`s — this is the standard
mechanism, not a workaround. `ServiceTokenFeignConfiguration` is
audience-parameterised so check-in reuses it with `aud = inventory-service` for
*its* command client.

**Ambiguous-origin calls are made explicit, never inferred.** A flight read can
happen inside a USER request *or* an event-driven flow, so the wrapper exposes
two methods rather than letting an interceptor guess from "does a servlet request
exist?":

```
flightClient.getFlightAsUser(id)     → propagate the incoming USER/ADMIN token
flightClient.getFlightAsService(id)  → request an aud=flight-service ROLE_SERVICE token
```

(equivalently, the resolved `Authorization` value is passed as an explicit Feign
method argument). Privilege is chosen by the **calling code path**, which knows
its origin — the transport layer never elevates on its own.

**Spring wiring rules (frozen, review 4).** Distinct `contextId`s alone do *not*
stop a `@Configuration`-annotated Feign config in a component-scanned package from
becoming a **parent** application bean whose `RequestInterceptor` leaks onto every
Feign client. So:

- **No authentication `RequestInterceptor` is registered as a global/parent
  bean.** The per-client config classes either live **outside the service's
  component-scan path** or are **not** annotated as application `@Configuration` —
  they are referenced only via `@FeignClient(configuration = …)`.
- **Every `@FeignClient` names its `configuration` explicitly** (query → user,
  command → service).
- **`ServiceTokenClient` opts out of parent configuration** so it can never
  inherit either interceptor:

  ```java
  @Configuration
  class ServiceTokenClientConfig {
      @Bean FeignClientConfigurer feignClientConfigurer() {
          return new FeignClientConfigurer() {
              @Override public boolean inheritParentConfiguration() { return false; }
          };
      }
  }
  ```

  It sends the client credential only — never a bearer token.
- **Filter single-registration.** If the shared `JwtAuthenticationFilter` is
  exposed as a plain servlet `Filter` bean, Spring Boot may auto-register it in
  the servlet container **outside** Spring Security, so it could run on
  `/api/auth/service-token` despite the separate `@Order(1)` chain. The
  auto-configuration therefore **either** constructs the filter only inside the
  intended `SecurityFilterChain(s)`, **or** disables container auto-registration
  with a `FilterRegistrationBean` (`setEnabled(false)`). It must run **once**, and
  **only** inside JWT-protected chains — never on the client-credential chain.
- **Tests capture the actual outbound header** (§15): query client → the exact
  incoming USER/ADMIN token; command client → an `aud=inventory-service`
  `ROLE_SERVICE` token; `ServiceTokenClient` → a client-credential request with
  **no** bearer token.

---

# 4. Authorization — Roles and Ownership

Three roles: **`USER`** (a passenger), **`ADMIN`** (back-office/reference-data),
**`SERVICE`** (a machine caller, §3.3). Authentication + role is **not enough** —
without object-level ownership, any authenticated USER who knows a booking id/PNR
could read, cancel, or refund another passenger's booking. So **ownership is in
scope for this branch** (review 1, correction #4).

## 4.1 Roles

- `User` gains a `role` column (`enum UserRole { USER, ADMIN }`, default `USER`)
  via the auth Flyway baseline+delta in §4.3.
- The public `RegisterRequest` has **no role field**, so `ADMIN` is never
  self-assignable. ADMIN is granted by the bootstrap property in §4.3, not a
  request body.
- The JWT gains a `roles` claim + a `token_type` (`user`|`service`); the shared
  validator maps them to `GrantedAuthority` (`ROLE_USER`/`ROLE_ADMIN`/
  `ROLE_SERVICE`). **A token with no recognized role/type fails closed** — it is
  rejected, never silently treated as `USER` (§5).

## 4.2 Ownership

- `Booking` gains **`ownerSubject`** — the immutable JWT subject (email) captured
  at booking creation from the authenticated principal. The free-form
  `customerId` is **never** trusted as ownership evidence.
- Enforcement:

  ```
  USER    → ownerSubject must equal authentication.name (the token subject)
  ADMIN   → may access any booking
  SERVICE → internal operations only (see matrix), not arbitrary user reads
  ```

- **Ownership must reach payment and check-in too** (review 2, blocker #4). Those
  services don't know the owner today. `ownerSubject` is snapshotted into
  **`Payment.ownerSubject`** and **`CheckIn.ownerSubject`** when each service
  consumes its event — so each enforces ownership from its own row, no
  cross-service lookup.
- **`ownerSubject` goes on the event for CREATED, CONFIRMED, *and* CANCELLED**
  (review 3), not just "BookingCreated": payment consumes `CREATED` but **check-in
  manifest creation consumes `CONFIRMED`** (verified), and a consumer of
  `CANCELLED` may need it too. The shared `BookingEventProducer.publish(...)`
  private method builds every event type, so `BookingEvent.ownerSubject` is set
  **there once** and is present on all three.
- **Legacy rows** (`ownerSubject = null` in booking, payment, or check-in):
  **ADMIN/SERVICE only** across all three — a USER cannot act on an unowned
  object. Safest documented policy; a one-time backfill is out of scope (no
  reliable subject for pre-branch rows).

## 4.3 Auth Flyway + admin bootstrap

- auth-service currently runs **`ddl-auto: update` with no migrations at all**
  (verified: no `db/migration` dir, no flyway block in its yml) though the Flyway
  deps are present. It adopts the same safe pattern proven in booking-service:

  ```
  V1__baseline_auth_schema.sql                    # full users table as it exists today
  V2__add_user_role_and_email_normalization.sql   # add role (nullable) →
                                                  # UPDATE all rows to 'USER' →
                                                  # SET NOT NULL + CHECK (USER|ADMIN);
                                                  # email collision pre-check (below) →
                                                  # UPDATE email = lower(trim(email)) →
                                                  # UNIQUE(email) + CHECK(email = lower(trim(email)))
  ```

  with `baseline-on-migrate: true`, `baseline-version: 1`, and
  **`ddl-auto: validate`**. Both a fresh DB (V1+V2) and the existing populated DB
  (baselined at 1, V2 runs) converge.
- **Email-normalization collision policy** (review 2, fix #6). Before the
  `lower(trim())` UPDATE, V2 checks for rows that would collapse together:

  ```sql
  SELECT lower(trim(email)) e, count(*) FROM users GROUP BY e HAVING count(*) > 1;
  ```

  If any exist the migration **aborts with a clear error** (a `DO $$ … RAISE
  EXCEPTION $$` guard) — it never silently deletes or merges accounts; a human
  resolves the duplicates first. After normalization, **both** `UNIQUE(email)`
  and `CHECK (email = lower(trim(email)))` are added, so a future direct DB write
  can't bypass application-side normalization.
- **Admin bootstrap is a property, not hardcoded SQL** (a migration promoting a
  known email is brittle — that row may not exist when it runs). At startup
  auth-service reads **`SKYBOOK_BOOTSTRAP_ADMIN_EMAIL`**: if that user exists and
  is not already ADMIN, promote once (idempotent); log a **loud warning if no
  administrator exists** in the system at all.

## 4.4 Frozen endpoint authorization matrix

Enforced with URL + method security per service (finalized here, **not** "during
the build step"). `SERVICE` = machine caller with a `ROLE_SERVICE` token;
`OWNER` = USER constrained to their own `ownerSubject` (ADMIN bypasses the owner
check).

| Endpoint | USER | ADMIN | SERVICE |
|---|---|---|---|
| **auth** `POST /api/auth/register`, `/login` (gateway routes ONLY these two) | public | public | — |
| `POST /api/auth/service-token` (de-routed from gateway; internal only) | **CLIENT_CREDENTIAL** — per-service client secret, BCrypt-verified; issues only for an allowlisted audience | | |
| **flight** `GET /api/flights/**`, `/api/flight-schedules/**`, `/search` | ✓ | ✓ | ✓ |
| `POST/PUT/PATCH/DELETE /api/flights/**`, `/api/flight-schedules/**` (create/update/cancel) | — | ✓ | — |
| **inventory** `GET /seat-map`, `/cabins`, `/flight/{id}` | ✓ | ✓ | ✓ |
| `POST /inventory` (create), close/reopen, block; `POST /aircraft`, `/seat-map`, seat status | — | ✓ | — |
| `POST /inventory/hold`, `/holds/auto`, `/release`, `/reservations`, `/reservations/cancel` | — | ✓ | ✓ (booking/checkin call these) |
| **booking** `POST /bookings` (create), `/quote` | ✓ | ✓ | — |
| `GET /bookings/{id}`, `/reference/{pnr}` | OWNER | ✓ | — |
| `GET /bookings` (list all), `/search` | — | ✓ | — |
| `PATCH /bookings/{id}/cancel` | OWNER | ✓ | — |
| `PATCH /bookings/{id}/confirm`, `/complete` | — | ✓ | — (event confirm calls the service method directly, not HTTP) |
| `PATCH /bookings/{id}/passengers/{pid}/check-in`, `/board` | OWNER | ✓ | — |
| **checkin** `GET /checkins/{id}`, `/booking/{id}`, `PATCH /{id}/checkin`, `/{id}/seat`; boarding-pass `GET /{id}`, `/checkin/{id}`, `/verify`; baggage `GET`/`POST` | OWNER | ✓ | — |
| `PATCH /checkins/{id}/open`, `/{id}/gate`, `/{id}/board`; `GET /checkins/flight/{id}`; manifests `GET`/`POST /{id}/finalize` | — | ✓ | — |
| `POST /checkins` (manual manifest creation) | — | ✓ | — (normal creation is an internal method call from the Kafka consumer) |
| **payment** `GET /payments/{id}`, `/reference/{ref}`, `/booking/{id}`, `/{id}/history`; `PATCH /{id}/authorize`, `/{id}/capture`; invoices `GET`; refunds `GET` | OWNER | ✓ | — |
| `POST /payments` (manual create), `PATCH /{id}/cancel`, `/{id}/refund` | — | ✓ | — (normal payment lifecycle is event-driven internal method calls) |
| **actuator** `/livez`, `/readyz` (MAIN port only) | public | public | public |
| **actuator** `/actuator/health`, `/metrics`, `/prometheus`, `/info` (MANAGEMENT port) | internal management port only, never host-published (§7) | | |

Principle: **writes to shared reference data (aircraft, flights, inventory
creation) and every back-office/gate/manual-lifecycle HTTP operation are ADMIN; a
passenger's own booking/check-in/payment reads and self-service lifecycle are
OWNER; reads of reference data are any authenticated. `SERVICE` is used ONLY for
service→service Feign calls (the inventory hold/reserve/release endpoints) — the
event-driven booking-confirm / payment-lifecycle / manifest-creation paths are
in-process method calls, NOT HTTP, so they need no HTTP role at all.**

## 4.5 Security-critical persistence (migrations, review 4)

`ownerSubject` and the service-client registry are security state, so they get
**real Flyway migrations**, not `ddl-auto` — and the two services still on
`ddl-auto: update` (verified: payment, check-in — neither has any migration) move
to the proven baseline+delta pattern with `ddl-auto: validate`.

- **auth `V3__create_service_clients.sql`** — the client-credential registry:

  ```
  service_clients
    client_id          PK
    secret_hash        NOT NULL        -- BCrypt/Argon2, never plaintext
    allowed_audiences  NOT NULL        -- e.g. 'flight-service,inventory-service'
    enabled            NOT NULL DEFAULT true
    created_at / updated_at
  ```

  **No environment-specific credentials are committed in migration SQL.** Rows are
  provisioned by **required deploy properties carrying pre-generated BCrypt
  hashes**, or a **one-time admin bootstrap command** that hashes a supplied
  secret. The token request carries only the requested audience; identity is the
  authenticated `client_id`. Invalid-client and invalid-secret responses are
  **indistinguishable** (constant-time, same error).
- **booking `V5__add_owner_subject.sql`** — `owner_subject varchar` **nullable**
  (legacy rows stay null → ADMIN-only). booking already runs Flyway `validate`.
- **payment: baseline + delta** — `V1__baseline_payment_schema.sql` (current
  schema) + `V2__add_owner_subject.sql` (nullable); `baseline-on-migrate: true`,
  `baseline-version: 1`, **`ddl-auto: validate`**.
- **check-in: baseline + delta** — `V1__baseline_checkin_schema.sql` +
  `V2__add_owner_subject.sql` (nullable); same Flyway config.
- **New rows/events must always populate `ownerSubject`**; only pre-branch rows
  are null. The value is captured at booking creation from the authenticated
  principal and rides the event (§4.2) to payment/check-in.

---

# 5. JWT Hardening

- **Asymmetric RS256** (§3.3) — auth-service signs with a private key using an
  explicit `Jwts.SIG.RS256`; every validator **rejects any token whose header
  `alg` is not RS256** (defends against `alg:none` and algorithm-substitution,
  incl. the RS→HS confusion attack, since validators hold only the public key and
  never an HMAC secret). Replaces the old `.signWith(getSigningKey())` HS384 path.
- **Boot-time key check.** auth-service fails startup if `JWT_PRIVATE_KEY` is
  missing/malformed or the key is < 2048-bit; every service fails startup if
  `JWT_PUBLIC_KEY` is missing/malformed or equal to the known dev placeholder.
  Fail closed, loudly, at boot. Keys minted with
  `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048` (+ matching
  public key).
- **Environment-specific issuer + the two-rule audience model** (review 2,
  blocker #3). Issuer is configurable — **`JWT_ISSUER`** (e.g.
  `skybook-auth-prod`). Audience is **not one value**: a user token's `aud` and a
  service token's `aud` mean different things, so each service configures both:

  ```yaml
  skybook:
    security:
      user-audience:    skybook-api-prod     # what USER/ADMIN tokens must carry
      service-audience: inventory-service     # this service's own name; the aud a
                                              # ROLE_SERVICE token must carry to reach it
  ```

  Validation rule by token type:

  ```
  token_type = user     → aud must contain  user-audience   (skybook-api-prod)
  token_type = service  → aud must contain  service-audience (this exact service)
  ```

  Auth-service issues a service token only for an audience the authenticated
  client is allowlisted for (§3.3).
- **The gateway accepts only `token_type = user` with `ROLE_USER`/`ROLE_ADMIN`
  and explicitly rejects `token_type = service`** — otherwise a machine token
  could enter through the public gateway and reach routes unrelated to its
  intended internal audience.
- **Full validation checklist** (shared `JwtTokenValidator`, all required):

  ```
  signature valid (RS256, public key)
  alg == RS256
  iss == configured JWT_ISSUER
  aud matches the token-type rule above
  sub present
  token_type present and recognized (user | service)
  token_type ↔ role COHERENCE (below)   → else FAIL CLOSED (never default to USER)
  exp in the future
  iat present
  ```

  **Token-type ↔ role coherence** — a recognized type *and* a recognized role is
  not enough; only these exact combinations are valid, everything else (incl.
  mixed-role tokens) is rejected:

  ```
  token_type = user     → roles is exactly { ROLE_USER } or { ROLE_ADMIN };  never ROLE_SERVICE
  token_type = service  → roles is exactly { ROLE_SERVICE };                 never ROLE_USER/ROLE_ADMIN
  ```

  e.g. `{"token_type":"service","roles":["ROLE_SERVICE","ROLE_ADMIN"]}` → rejected.

- **TTL (settled, review 2):** **USER/ADMIN access token = 60 min**;
  **`ROLE_SERVICE` token = 10 min, auto-refreshed** by `ServiceTokenProvider`
  (invisible to users, so it stays short). With no refresh-token mechanism a
  15-min user token caused needless re-login during Postman/E2E/demos; 60 min is
  the better v1 balance. Once refresh-token rotation exists (v2, §14) the user
  token drops back to 15 min with a longer-lived, rotated, revocable refresh
  token.
- `env.example` documents `JWT_PRIVATE_KEY` (auth only), `JWT_PUBLIC_KEY` (all),
  `JWT_ISSUER`, and the per-service client credentials, each with a generation
  one-liner.

---

# 6. Auth Surface Hardening

- **Validation, split by request.**
  - `RegisterRequest` — `@Email`, `@NotBlank`, and a **password complexity
    policy** (`@Size(min=12)` + upper/lower/digit/symbol pattern).
  - `LoginRequest` — `@Email` + password **`@NotBlank` only**. Applying the new
    complexity rule at *login* would lock out accounts created under the old
    policy; complexity belongs on registration.
  - `@Valid` on the controller; a `MethodArgumentNotValid` handler returns `400`
    with field messages (the fleet `ErrorResponse` shape).
- **Email identity consistency** — normalize `email.trim().toLowerCase(Locale.ROOT)`
  before *both* register and login, so `Alice@X.com` and `alice@x.com` are one
  account. Uniqueness is enforced by a **DB unique index on the normalized
  email** (V2, §4.3), not only `existsByEmail`; the register path also **catches
  the unique-constraint violation** (concurrent double-register race) and
  translates it to the same `409`.
- **Safe errors** — add a `@RestControllerAdvice` to auth-service (it has none):
  - duplicate email → **`409 Conflict`**, generic "email already registered"
  - bad credentials → **`401 Unauthorized`**, **identical** generic message for
    both "no such user" and "wrong password" (no user enumeration)
  - stop throwing bare `RuntimeException`; introduce typed
    `EmailAlreadyRegisteredException` / `InvalidCredentialsException`.
- Remove the commented-out plaintext-password line (§2.4).
- **Login response** stays a bare token string for wire-compatibility (gateway +
  Postman flows depend on it) — contract unchanged this branch.

---

# 7. Actuator / Management Hardening

**Decision: a separate management port, never host-published** — this avoids
trying to rotate a JWT inside Prometheus.

- Setting **`management.server.port`** (e.g. `909x`) **moves the entire actuator
  surface off the main port** onto that port — actuator endpoints do **not**
  automatically stay on the main app port (review 2, fix #5). That port is
  `expose:`-only on the compose/k8s network, **never host-mapped**.

  ```
  main port         → business API only
  management port   → health, liveness, readiness, metrics, prometheus, info
                      (internal network only, never host-published)
  ```

- **Docker health checks and Prometheus target the management port.** Prometheus
  already scrapes over the internal network, so it keeps working; no token
  needed because the surface isn't reachable from outside.
- **For Kubernetes**, liveness/readiness are additionally re-exposed on the
  *main* server (so the kubelet's main-port probes keep working) with:

  ```yaml
  management:
    endpoint:
      health:
        probes:
          add-additional-paths: true   # /livez, /readyz on the MAIN port
  ```

  — other actuator endpoints stay on the management port only.
- `health` details restricted to `when-authorized`.
- Net effect: metrics/prometheus/circuitbreakers are unreachable from the host
  entirely; §3.1 already removes the business ports — belt and braces.

---

# 8. Container Hardening

Applied to the shared Dockerfile template, rolled to all 8 service images:

- Add a non-root user and `USER` directive:
  `RUN useradd -r -u 1001 skybook && chown -R skybook /app` → `USER skybook`.
- Compose/k8s run with `read_only: true` root filesystem + a writable `tmpfs`
  for `/tmp` (Spring Boot needs a writable temp dir); k8s
  `securityContext: { runAsNonRoot: true, readOnlyRootFilesystem: true,
  allowPrivilegeEscalation: false, capabilities: { drop: [ALL] } }`.
- Keep the JRE base image but pin by digest in a follow-up (noted, not blocking).
- Verify each service still boots and passes health under the locked-down runtime
  (the failure mode is a service that writes outside `/tmp`).

---

# 9. Supply-Chain Scanning in CI

- **Tooling: Trivy for both** dependency (`fs`) and image scans — one tool,
  native SARIF (§12.F).
- **Dependency scan** — Trivy `fs` over the **resolved/built Maven artifacts
  after `mvn clean verify`** (not just the source `pom.xml`), so transitive and
  actually-packaged dependencies are what's scanned.
- **Scan-before-push ordering** (review 1) — the current image job runs only on
  `main` and pushes immediately. The secure order:

  ```
  PR to main:   build image locally → Trivy scan → DO NOT push
  push to main: build image → Trivy scan → push ONLY after the scan passes
  ```

  An image is never pushed before it is scanned.
- **Gate** — fail on **high/critical (CVSS ≥ 7)**; a committed suppression file
  holds accepted findings so the gate stays actionable, not a day-one wall of red.
- **SARIF upload** to the GitHub Security tab (same `if: always()` discipline as
  the test-report upload); the job needs **`permissions: security-events: write`**.
- Runs on PRs to `main` like the rest of CI — so this very branch is scanned.

# 10. Committed Secret Defaults — Removed

The compose file ships insecure fallbacks that must go (all verified present):

| Location | Today | Fix |
|---|---|---|
| `postgres` env (line 7) | `POSTGRES_PASSWORD: postgres` (hardcoded) | `${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}` |
| `checkin` env | `CHECKIN_BOARDING_PASS_KEY:-dev-only-insecure-signing-key-change-me` | `${CHECKIN_BOARDING_PASS_KEY:?required}` |
| `grafana` env | `GRAFANA_ADMIN_PASSWORD:-admin` | `${GRAFANA_ADMIN_PASSWORD:?required}` |
| `auth` env | `JWT_SECRET: ${JWT_SECRET}` (HMAC, retired) | `JWT_PRIVATE_KEY: ${JWT_PRIVATE_KEY:?required}` + `JWT_PUBLIC_KEY: ${JWT_PUBLIC_KEY:?required}` |
| every other service env | `JWT_SECRET` (shared, forgeable) | `JWT_PUBLIC_KEY: ${JWT_PUBLIC_KEY:?required}` only — no private key, no HMAC secret |
| `booking`/`checkin`/`payment`/**`inventory`** env | (none) | `SKYBOOK_SERVICE_CLIENT_ID` + `SKYBOOK_SERVICE_CLIENT_SECRET:?required` (per-service, §3.3) — inventory included because of `inventory-service → flight-service` in the allowlist |

Using the `${VAR:?message}` form makes compose **fail fast with a clear error**
if a required secret is unset, instead of silently booting with a known-bad
default. The move to RS256 (§3.3/§5) also means only auth-service carries a
signing key at all. `env.example` documents each with a real-generation one-liner.

**The boarding-pass signing key gets the same boot-time minimum-strength
validation** (§5): check-in rejects startup if `CHECKIN_BOARDING_PASS_KEY` is
missing, too short, or the known dev default.

---

# 11. What This Branch Deliberately Does **Not** Do (v1 boundaries)

- **No refresh tokens / token revocation / logout / JWKS rotation** — RS256 is in
  (§3.3/§5), but the public key is static config and there is no revocation list;
  token rotation is a v2 item (§5, §14).
- **No secrets manager** (Vault/SOPS/sealed-secrets) — env-var secrets stay; the
  hardening is boot-time validation + fail-fast on unset + not committing
  defaults (§5, §10).
- **No mTLS between services** — network isolation + JWT propagation +
  `ROLE_SERVICE` is the v1 posture (§3).
- **No WAF / DDoS / bot protection** — the gateway rate limiter is the v1 answer.

Note: **booking ownership IS in scope** (§4.2) — it moved in after review 1;
authentication+role alone left an object-level authorization hole. Half-building
any of the *above* is worse than scoping them out cleanly; each is a legitimate
follow-up branch (§14).

---

# 12. Decisions Settled (rounds 1–4)

All open forks are now resolved into the design above; recorded for traceability:

- **A. `skybook-security`** — **Spring Boot auto-configuration** module; gateway
  migrates onto its shared validator; each service keeps its own local
  `SecurityConfig` (§3.2).
- **B. Feign auth** — identity chosen **per operation**: user-token propagation
  for reads, the caller's own `ROLE_SERVICE` token for inventory writes; not
  "does a request context exist" (§3.3, R2 blocker #2).
- **C. ADMIN grant** — **`SKYBOOK_BOOTSTRAP_ADMIN_EMAIL` property**, idempotent,
  loud warning if no admin exists (§4.3).
- **D. Ownership** — **in scope**, propagated via `ownerSubject` on the event
  into `Booking`/`Payment`/`CheckIn`; legacy null-owner rows ADMIN/SERVICE-only
  (§4.2, R2 blocker #4).
- **E. TTL (settled)** — **USER/ADMIN 60 min, SERVICE 10 min auto-refreshed**
  (§5, R2). 15-min user tokens are a v2 item paired with refresh rotation.
- **F. Scanner** — **Trivy** for both fs + image, native SARIF (§9).
- **G. Signing (R2 blocker #1)** — **asymmetric RS256**; auth holds the private
  key, everyone else the public key; per-service client credentials + caller→
  audience allowlist; `sub` derived from the authenticated client (§3.3, §5).
- **H. Audience (R2 blocker #3)** — **two rules**: user `aud = skybook-api-*`,
  service `aud = exact receiving service`; gateway rejects `token_type=service`
  (§5).
- **I. Feign identity binding (R3 blocker #1)** — frozen at the **client
  interface** level (`InventoryQueryFeignClient` vs `InventoryCommandFeignClient`
  + isolated `ServiceTokenClient`), explicit `…AsUser`/`…AsService` for
  ambiguous origins; no global privilege-inferring interceptor (§3.3).
- **J. Token endpoint isolation (R3 blocker #2)** — gateway route narrowed to
  `/register`+`/login`; `@Order(1)` client-credential chain, BCrypt-hashed
  per-client secrets, matrix label `CLIENT_CREDENTIAL` (§3.3).
- **K. inventory→flight identity (R3 blocker #3)** — ADMIN-token propagation
  (service token if ever scheduler-origin); verified before flipping flight
  (§3.3, §13).
- **L. Enforcement ordering (R4)** — roles, token claims, and ownership
  schema/propagation all land **before** `authenticated()` is flipped; the strict
  validator ships with token issuance (§13).
- **M. Feign/filter Spring wiring (R4)** — no global auth interceptor; per-client
  configs off component-scan + named per `@FeignClient`;
  `ServiceTokenClient.inheritParentConfiguration()=false`; JWT filter registered
  once, only inside JWT-protected chains (§3.3).
- **N. Security-critical persistence (R4)** — `service_clients` (auth `V3`,
  BCrypt, provisioned via deploy props/bootstrap) + `owner_subject` migrations
  (booking `V5`; payment/check-in baseline+delta); all three on
  `ddl-auto: validate` (§4.5).

---

# 13. Build Order

**Reordered after review 4: every token claim, role, and ownership column the
matrix relies on exists BEFORE enforcement is flipped** — otherwise flipping
`authenticated()` would either enforce authn-only (admin/ownership rules absent)
or reject every user whose token lacks `roles`/`token_type`. The strict validator
ships *with* token issuance, not after it. The
`skybook.security.enforcement-enabled` flag (default `true`, off only in the test
profile) lets each chain be built and merged before it's switched live.

1. **RS256 keys + shared `skybook-security` infrastructure — build only, do NOT
   swap the live gateway validator yet** (§3.3, §5, §3.2). Generate the RSA
   keypair; build the module: `JwtTokenValidator` with the **full §5 checklist
   (alg-pin, two-rule audience, token_type↔role coherence)**, filter,
   entry-point/denied handlers, `JwtSecurityProperties`, `ServiceTokenProvider`,
   registered via `AutoConfiguration.imports`. Nothing is wired into a live chain
   in this step — the gateway keeps its current validator, because auth-service
   cannot yet issue a token the new validator would accept (that arrives in
   step 2). **Enforcement stays off.**
2. **Atomic checkpoint: hardened RS256 issuance + gateway migration together**
   (§4.3, §4.1, §5). auth Flyway **V1 baseline + V2** (`role`: nullable →
   backfill `USER` → NOT NULL + CHECK; email collision-abort → normalize →
   `UNIQUE` + `CHECK`), `ddl-auto: validate`, bootstrap-admin property; issuance
   emits **`roles`, `token_type=user`, `iss`, `aud=user-audience`, 60-min TTL**.
   **In the same commit**, migrate the gateway onto the shared validator — issuer
   and verifier change as one unit, never separately. **Verify register → login →
   a protected gateway endpoint end-to-end before committing**, so there is no
   checkpoint where the gateway demands claims auth doesn't yet mint. (Steps 1–2
   may be merged into a single implementation checkpoint if that's cleaner.)
3. **Client-credential registry + `/service-token` endpoint, isolated** (§3.3,
   §4.5) — auth **V3 `service_clients`** table; **narrow the gateway route to
   `/register`+`/login`** (drop `/api/auth/**`); `@Order(1)` client-credential
   `SecurityFilterChain` for `/service-token` (BCrypt-verified, `sub`+allowlist
   from the authenticated client id, no JWT); issues a 10-min `ROLE_SERVICE`
   token (`token_type=service`, `aud=<target>`).
4. **Ownership schema + event wiring, with `SecurityContext` available for the
   capture** (§4.2, §4.5). Two ordered parts so a freshly created booking can
   never snapshot a null owner while the rest of the matrix is still off:
   1. **Migrations + event plumbing first** — booking **V5** adds `owner_subject`
      (nullable); **payment + check-in get baseline+delta migrations** adding
      `owner_subject`, all three moving to `ddl-auto: validate`;
      `BookingEvent.ownerSubject` set in the shared `publish(...)` for **CREATED,
      CONFIRMED, CANCELLED** and snapshotted on consume.
   2. **Turn booking-service's JWT filter on in authentication-only mode** (valid
      bearer → populate `SecurityContext`; **absent** bearer → still allowed while
      authorization is off; **present-but-invalid** → reject) **and make the
      booking-create endpoint `authenticated()`**. Only then does
      `createDraftBooking` capture `ownerSubject` from the (now guaranteed)
      principal — so the invariant "only legacy rows are null" holds from the
      first new booking. The rest of the matrix still flips at step 6. *(Simpler
      alternative if preferred: keep only the schema/event migrations here and
      move the `ownerSubject` **capture** code into step 6, so capture and
      enforcement activate atomically — either way, capture never runs before a
      `SecurityContext` exists.)*
5. **Feign identity split + service-token provider** (§3.3) —
   `InventoryQueryFeignClient` (`UserTokenFeignConfiguration`) +
   `InventoryCommandFeignClient` (`ServiceTokenFeignConfiguration`,
   aud=inventory-service), distinct `contextId`s; isolated `ServiceTokenClient`
   (`inheritParentConfiguration()=false`, no interceptor); explicit
   `getFlightAsUser`/`getFlightAsService`. Verify booking→flight (user),
   booking→inventory + checkin→inventory (service), and **inventory→flight
   (ADMIN-propagated)** — all while enforcement is still off, asserting the
   actual outbound header per client.
6. **Flip services to the full authentication + authorization matrix** (§3.2,
   §4.4) — each service's local `SecurityConfig` (STATELESS, OPTIONS permit,
   formLogin/httpBasic disabled) with the **complete role/OWNER/SERVICE rules**,
   one dependency chain at a time. Add security to flight/notification; gateway
   rejects `token_type=service`; stop trusting `X-Auth-User`. **flight-service is
   flipped only after step 5's inventory→flight path is proven**, or aircraft/
   inventory admin 401s internally.
7. **Full gateway + direct-service E2E** — every seat-selection/booking/check-in
   flow still passes *through the gateway*; a direct-to-service call → 401; a
   forged `X-Auth-User` alone → 401; a forged/locally-signed token → rejected (no
   private key downstream); a USER token to `/inventory/hold` → 403; cross-user
   booking/payment/checkin access → 403.
8. **Network isolation** (§3.1) — unpublish all internal ports incl.
   Postgres/Kafka + management ports; document the override file.
9. **Auth surface** (§6) — split validation (register complexity, login
   `@NotBlank`), typed exceptions, `@RestControllerAdvice`, remove dead line.
10. **Actuator hardening** (§7) — actuator moved to a separate internal-only
    management port; k8s probe paths re-exposed on main.
11. **Committed secret defaults removed** (§10) — `${VAR:?required}`;
    boarding-pass key boot-strength check.
12. **Container hardening** (§8) — non-root + read-only rootfs, roll to 8 images,
    live boot verification.
13. **CI scanning** (§9) — Trivy fs (post-`verify` artifacts) + image
    (scan-before-push), SARIF upload, `security-events: write`, fail-on-high.
14. **Design doc → Implemented + Implementation Notes**, house pattern; full
    reactor `mvn clean verify` + live compose e2e certification.

---

# 14. Follow-Ups (explicitly deferred)

- **Refresh tokens + revocation + logout** — then the user access token drops
  back to 15 min (§5); pairs with **key rotation / JWKS** for the RS256 public key
  (static config in v1).
- **On-behalf-of tokens** (RFC 8693 `act` claim) so internal inventory writes can
  carry the originating user for downstream audit attribution, instead of the
  caller's bare `ROLE_SERVICE` token (§3.3).
- **One-time backfill of `ownerSubject`** for legacy booking/payment/check-in
  rows (out of scope now; §4.2 makes them ADMIN-only meanwhile).
- **Secrets manager** (Vault / SOPS / sealed-secrets on k8s) — incl. the RS256
  private key.
- **mTLS** between services (service mesh).
- **Image digest pinning** + SBOM generation/publishing.

---

# 15. Testing Plan

| Layer | What's tested |
|---|---|
| Per-service auth | no/invalid token to any business service → 401; valid token → 200 (unit: filter; IT: full chain) |
| Bypass closed | direct-to-service call (not via gateway) with no token → 401; forged `X-Auth-User` alone → still 401 |
| Roles | USER hitting an ADMIN surface (create aircraft/inventory/flight) → 403; ADMIN → 200 |
| Ownership | USER reading/cancelling **another** user's booking → 403; own → 200; ADMIN any → 200; legacy null-owner + USER → 403 — asserted in booking **and** payment (`/authorize`, `/capture`) **and** check-in (`/checkin`, `/seat`) |
| Asymmetric forgery | a token signed with any key other than auth's private key → rejected everywhere (services hold only the public key); `alg:none` and RS→HS confusion → rejected; a downstream service cannot mint any valid token |
| Service identity | `ROLE_SERVICE` accepted only on matrix-allowed internal endpoints; no-token/no-service-request call site → fails closed (401); **auth issues a service token only for an allowlisted (caller→audience) pair, `sub` from the authenticated client credential**; booking's credential cannot obtain a `payment-service`-audience token |
| Audience + role model | USER token (`aud=skybook-api-prod`) → **GET /inventory/cabins accepted**, **POST /inventory/hold → 403** (denied by *role*, not audience); SERVICE token (`aud=inventory-service`) → POST hold **accepted**; SERVICE token (`aud=flight-service`) sent to inventory → **401** (wrong service-audience); `token_type=service` at the public gateway → rejected |
| Token coherence | `token_type=user` carrying `ROLE_SERVICE`, or `token_type=service` carrying `ROLE_ADMIN`, or a mixed-role `{ROLE_SERVICE,ROLE_ADMIN}` token → all rejected |
| JWT hardening | auth boot fails on missing/malformed/<2048-bit private key; service boot fails on missing/default public key; `alg` ≠ RS256 → rejected; wrong `iss` → rejected; missing role/token_type → rejected (not defaulted); expired → rejected; 60-min USER / 10-min SERVICE TTLs asserted |
| Auth surface | invalid email/blank/short password → 400 field messages; duplicate email → 409; concurrent double-register race → 409 (DB unique); wrong password AND unknown user → **identical** 401; `Alice@X.com` and `alice@x.com` resolve to one account; login password `@NotBlank` only (old-policy accounts still log in) |
| Auth migration | fresh DB → V1+V2; existing populated DB → baselined at 1, V2 backfills every user to `USER` then NOT NULL; **email-collision pre-check aborts the migration** when two rows collapse together; `CHECK(email = lower(trim(email)))` present; `ddl-auto: validate` passes both; `SKYBOOK_BOOTSTRAP_ADMIN_EMAIL` promotes once, warns when no admin exists |
| Feign identity | booking→flight carries the **user** token; booking→inventory + checkin→inventory carry the caller's **`ROLE_SERVICE`** token; a USER token sent to `/inventory/hold` → **403** (proves the propagation/matrix fix); service token refreshed before expiry (not per call) |
| Actuator | main port serves business API + only `/livez`/`/readyz` (probe paths); all other actuator incl. `prometheus`/`metrics` **only** on the internal management port, unreachable from the host; Prometheus scrape (management port) still works |
| Secrets fail-fast | compose with `POSTGRES_PASSWORD`/`JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY`/`SKYBOOK_SERVICE_CLIENT_SECRET`/`CHECKIN_BOARDING_PASS_KEY`/`GRAFANA_ADMIN_PASSWORD` unset → fails to start with a clear message; check-in rejects a weak/default boarding-pass key at boot |
| Container | each image runs as non-root (`id` ≠ 0) with read-only rootfs and still passes health; writes confined to the `/tmp` tmpfs |
| CI scanning | Trivy fs (post-`verify` artifacts) + image steps run on this PR and **do not push**; a seeded high CVE fails the gate; SARIF appears in the Security tab |
| Regression | full reactor `mvn clean verify` green; the complete seat-selection + booking + check-in e2e still passes through the gateway end to end |

---

# 16. Risks

- **Breaking working flows** is the top risk — flipping every service to
  `authenticated()` before tokens propagate would 401 every internal call. This
  is exactly why the build order (§13) lands **hardened issuance + gateway
  migration atomically (step 2), the Feign identity split + `ROLE_SERVICE`
  (step 5), and only then the full-matrix flip (step 6)**, behind the default-on
  `enforcement-enabled` flag, with the full gateway + direct-service e2e at
  **step 7**.
- **Null `ownerSubject` on a new booking** if capture ran before a
  `SecurityContext` existed — closed by step 4.2: booking's JWT filter is turned
  on in authentication-only mode and the create endpoint made `authenticated()`
  *before* capture is introduced, so only pre-branch rows are ever null.
- **Accidental privilege elevation** — a service token used on a code path that
  should have carried a user token. Mitigation: `ROLE_SERVICE` is granted only on
  explicitly identified call sites and fails closed everywhere else (§3.3); the
  matrix (§4.4) names exactly which endpoints accept SERVICE.
- **Read-only rootfs boot failures** — a service writing outside `/tmp` fails to
  start; caught by the §8 live boot verification, fixed with a tmpfs mount.
- **Scanner noise** — first Trivy run may surface many transitive CVEs; the
  suppression file + fail-on-high (not medium) keeps the gate actionable.
- **Prometheus scraping** breaks if the management port moves without repointing
  the scrape; §7 keeps the scrape working over the internal network.
- **Legacy null-owner bookings** become USER-inaccessible (§4.2) — intended, but
  worth stating: any pre-branch booking can only be managed by ADMIN afterward.

---

# 17. Implementation Notes

All 14 build-order steps landed on `feature/security-hardening`, each with a
container rebuild + live verification + its own commit. Below is an honest
account of what the frozen design got slightly wrong or under-specified and a
closer pass caught — in the spirit of this project's other module post-mortems.

**Live certification.** A final end-to-end run against the fully-hardened running
fleet passed **23/23**: gateway is the only public surface; no-token / forged-sig
/ `X-Auth-User`-spoof → 401; every internal service port + Postgres (5432) +
Kafka (9092) + the management ports are unreachable from the host; an in-network
direct call to a service still needs a valid token (401); actuator is off the
main port (404) and only reachable on the internal management port; the role
matrix holds (USER → 403 on reference-writes / seat-ops / back-office, ADMIN →
200); a `token_type=service` token is refused at the gateway; and OWNER isolation
holds (A reads own 200, B reads A's 403, ADMIN override 200, anon 401). Full
reactor `mvn clean verify` is green.

**Deviations and things the first pass got wrong:**

1. **The step-5 Feign identity split was incomplete.** Only `booking→inventory`
   was split onto a `ROLE_SERVICE` token; `booking→flight`, `checkin→flight` and
   `checkin→inventory` were left as bare clients. This surfaced as a live 502 on
   booking-create the moment flight-service started enforcing auth ("could not
   reach flight-service to validate flight"). Caught precisely because the build
   order enforces **flight last**; fixed by giving every cross-service client the
   correct outbound identity (user-token propagation for reads, service-token for
   inventory writes).

2. **Only booking and checkin actually make outbound service calls.** The §10
   env table lists client secrets for booking/checkin/payment/inventory, but in
   the built system payment and inventory make no outbound `ROLE_SERVICE` calls —
   their secrets are consumed only by **auth-service's client registry** (which
   seeds all four). So all four secrets are required in *auth's* env; only
   booking and checkin additionally carry their own. inventory→flight turned out
   to be a user-token read, not a service call.

3. **`/service-token` bad credentials returned 403, not 401.** Spring Security 6
   turns an unauthenticated request tripping `.authenticated()` into an
   access-denied (403) via the anonymous authentication, not an auth challenge.
   Fixed with an explicit `HttpStatusEntryPoint(UNAUTHORIZED)` on the
   client-credential chain, so bad / unknown / missing client credentials are now
   an identical, indistinguishable **401** (no client enumeration).

4. **`management.endpoint.health.probes.add-additional-paths: true` is not enough
   on its own.** After moving actuator to the management port, `/livez` and
   `/readyz` still 401'd — the liveness/readiness probe groups have to be
   *enabled* (`probes.enabled: true`) before the additional paths resolve.

5. **Main-port actuator returns 401, not 404, once moved.** With actuator on the
   management port, `/actuator/**` on the main port has no handler → Spring
   forwards to `/error`, which the security chain guards → 401. It is genuinely
   not served on the main port; the status is just 401 rather than 404.

6. **An imported BOM ignores a version-property override.** Because the reactor
   *imports* `spring-boot-dependencies` (rather than inheriting the parent),
   setting `<postgresql.version>` in `<properties>` had no effect. The pin had to
   be an explicit `dependencyManagement` entry declared **before** the BOM import
   (first-declared wins).

7. **The Trivy gate paid for itself on day one.** The first scan surfaced three
   *fixable* findings — pgjdbc 42.7.11 (CVE-2026-54291, SCRAM downgrade MITM),
   bouncycastle 1.80 (CVE-2025-14813, GOSTCTR, CRITICAL) and commons-fileupload
   1.5 (CVE-2025-48976, DoS), the latter two transitive via
   `spring-cloud-starter`. All three were pinned to fixed versions; the
   `.trivyignore` stayed empty. `ignore-unfixed: true` keeps the gate on fixable
   issues rather than a wall of unpatchable base-OS noise.

8. **Postgres password propagation.** Services hard-coded `postgres/postgres` in
   `application.yml`, so making `POSTGRES_PASSWORD` a required, defaultless secret
   meant also injecting `SPRING_DATASOURCE_PASSWORD` into every service — not just
   the postgres container.

9. **`@WebMvcTest` auto-registers servlet `Filter` beans.** The auth-service
   controller slice failed to load a context because it pulled in the
   `JwtAuthenticationFilter` component (which needs `JwtService`); excluded via
   `excludeFilters` + `addFilters=false`.

10. **Windows PowerShell 5.1 gotchas** (tooling, not product): `Set-Content`
    re-encodes UTF-8 `§` to mojibake, and the `` `u{…} `` escape doesn't exist —
    both silently corrupted comments until the edits were redone with
    `[System.IO.File]::WriteAllText(..., UTF8Encoding($false))` and a literal
    `[char]0x00A7`.

**One known, unrelated flake:** `PaymentReferenceGeneratorTest.
collisionsAreRareAcrossManyGenerations` asserts 10 000 generated references are
*all* unique and occasionally hits a birthday-paradox collision (9 999). It is
`SecureRandom`-based and independent of anything in this branch; it passes on
re-run and is tracked as a separate cleanup (loosen the assertion to match the
test's own "rare" wording, or widen the reference entropy).

## 17.1 Pre-merge review fixes

A pre-merge security review caught several fail-closed gaps the first pass
missed. All were fixed in one review-fix commit before the PR was opened;
re-certified live afterward.

1. **JWTs with no `exp` were accepted.** jjwt only *checks* `exp` when present,
   so a token minted without one never expired. The shared `JwtTokenValidator`
   now rejects a missing `exp` explicitly (mirroring the existing `iat` check),
   with a regression test.

2. **A SERVICE token could read any user's booking (object-level escalation).**
   `SecurityAccess.requireOwnerOrAdmin` treats `ROLE_SERVICE` as privileged, and
   booking's owner routes were `authenticated()` - and `payment-service` was
   allowlisted for the `booking-service` audience, so a payment machine
   credential could mint a booking-service token and bypass ownership. Fixed at
   two layers: (a) `payment-service` is removed from the service-client registry
   entirely - it makes **no** outbound service-to-service HTTP calls (booking
   learns of payments via Kafka), so it needed no audience at all; and (b)
   booking now requires `hasAnyRole("USER","ADMIN")` on every non-ADMIN route, so
   a `ROLE_SERVICE` token is rejected at booking's edge regardless of audience
   (booking has no inbound service API). This is the object-level authorization
   hole review 1 warned authentication+role alone would leave. The registry
   bootstrap was also made **authoritative** - it now deprovisions (deletes) any
   DB client no longer in the config, so removing `payment-service` from config
   actually revokes it; previously the seeder was insert/update-only and a
   retired client kept its credential and audiences in the DB forever.

3. **auth did not verify its keypair was consistent.** The private key had a
   2048-bit floor but the public key parser did not, and the two were loaded
   independently. auth now bit-checks the public key too and fails boot if the
   private and public moduli do not match - otherwise auth would mint tokens the
   whole fleet rejects.

4. **The service-token fetch was not fail-closed.** `HttpServiceTokenFetcher`
   had no connect/read timeouts (a hung auth-service would stall the caller) and,
   on a parse failure, treated a malformed / `exp`-less token as valid for 60s.
   It now uses bounded timeouts (2s/5s) and throws on a malformed or `exp`-less
   token rather than caching it.

5. **Booking's Kafka-driven confirmation lost flight enrichment.** The
   `PAYMENT_SUCCEEDED -> confirm` path runs on a Kafka consumer thread with no
   incoming user token, so the user-token flight client 401'd and the
   confirmation event/email silently dropped its route details. Booking gained a
   second, service-token flight client (`FlightCommandFeignClient`, aud=
   flight-service) used only for this off-request-thread enrichment; the
   must-succeed create-path validation keeps propagating the user's token.
   (check-in has no equivalent gap - its booking-event consumer reads flight
   fields straight off the event, it does not call flight-service.)

6. **Committed insecure defaults in `application.yml` (not just compose).** The
   datasource password (`postgres`) and the per-service client secrets
   (`dev-*-secret`) were still hard-coded / defaulted in the service YAMLs, so a
   run outside compose silently used publicly known credentials. All are now
   `${VAR}` with no committed default (fail closed); tests supply explicit
   test-only values (Testcontainers `@DynamicPropertySource` for the datasource,
   test properties for the client secrets).

**Documented deviation (not a fix):** the frozen §4.4 matrix lists boarding-pass
GET/verify as OWNER/ADMIN, but the implementation makes those two operations
ADMIN-only. This is *more* restrictive, not an exposure - passenger self-service
boarding-pass retrieval is intentionally deferred (it needs ownership resolution
against the check-in's booking owner); tracked as a follow-up, and called out
here so the deviation from the matrix is on the record rather than silent.
