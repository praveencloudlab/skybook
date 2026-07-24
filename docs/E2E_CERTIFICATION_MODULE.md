# 🎯 SkyBook End-to-End Certification — Design

---

## Project Information

| | |
|---|---|
| **Scope** | Automate the full customer journey as an executable, repeatable certification suite — register → login → search → quote → book → hold seat → pay → confirm → check-in → boarding pass → board → finalise — plus the failure paths (cancellation, declined payment, duplicate requests, expired holds, service-down) and a real cross-service trace assertion |
| **Branch** | `feature/e2e-certification` |
| **Status** | 🔒 **FROZEN — approved.** All four §10 questions resolved (see §10 Decisions Settled). Implementation follows the §13 build order. |
| **Depends on** | Everything merged: dockerization, ci-cd, observability, resilience, seat-selection, security-hardening (all on `main`) |

Goal: today "does the whole thing actually work?" is answered by a human running
Postman requests by hand. This module turns that into **one command that proves
the platform works**, and — just as importantly — **proves it fails correctly**.

---

# 1. Load-Bearing Findings (traced against the live code, not assumed)

These shape the design; each was verified in the repo, not inferred.

1. **Seed data already exists and is substantial.** `scripts/seed/seed.sh` loads
   **10,950 flights**, 2 aircraft, 480 seats and one inventory row per flight
   (`docs/SEED_DATA.md`), spanning today→+365 days. The suite does **not** need to
   create reference data for the happy path — it needs to *find* a suitable flight.
2. **`seed.sh` is unaffected by the network isolation.** It uses
   `docker exec … psql`, not `localhost:5432`. Security hardening unpublished
   Postgres, but seeding still works unchanged. (Checked specifically because it
   looked like a likely breakage.)
3. **Only the gateway is reachable from the host** (`:8080`). Every service port,
   Postgres and Kafka are `expose:`-only. So the suite **must drive everything
   through the gateway** — which is the correct trust boundary anyway. Anything
   needing direct service/DB access must go through `docker compose exec`.
4. **Authorisation is now enforced.** Reference-data writes are ADMIN-only; the
   passenger journey is OWNER-scoped. So the suite needs **two identities**: an
   ADMIN (for any seeding/back-office assertions) and a fresh USER per run.
   ADMIN is granted only via `SKYBOOK_BOOTSTRAP_ADMIN_EMAIL` (§4.3 of the security
   module) — there is no API to promote a user.
5. **Deterministic failure triggers already exist in the code:**
   - `SimulatedPaymentGateway`: **amounts ending `.13` → declined** (`SIM_DECLINED`).
   - Payment creation accepts an **`Idempotency-Key`** header → duplicate-request path.
   - Fare-type refund rules exist on cancellation.
   These are real, in-product triggers — no test-only backdoors needed.
6. **Hold expiry is 15 minutes, swept every 60s** (`inventory.hold.sweep-interval-ms`,
   booking's stale-draft sweep aligned). **A 15-minute wait is not acceptable in a
   test suite** — see the open question in §10.1.
7. **The existing Postman collection (33 requests) predates two branches.** It has
   no check-in/boarding-pass requests at all, and its auth predates role
   enforcement (e.g. "Create flight" now needs ADMIN). It is a useful *manual/demo*
   artifact but is **not** a trustworthy certification baseline today.
8. **A known observability gap is explicitly assigned to this branch**: the
   Kafka-hop trace was only verified "by mechanism", never with a real completed
   booking, because no flights were seeded at the time. That is now fixable.

---

# 2. What "Certified" Means

The suite asserts three different classes of thing. Keeping them distinct matters,
because they need different assertion styles:

| Class | Example | Assertion style |
|---|---|---|
| **Synchronous contract** | `POST /api/bookings` → 201 + PNR | direct response assert |
| **Asynchronous effect** | payment captured → booking CONFIRMED → check-in rows created | **poll with timeout** (never `sleep`) |
| **Cross-cutting evidence** | one trace spans gateway→booking→Kafka→payment | query Tempo after the journey |

---

# 3. Harness — Technology Decision

**Recommendation: a new `e2e-tests` Maven module in the reactor**, JUnit 5 +
RestAssured + Awaitility, driving `http://localhost:8080` only.

Why this over the alternatives:

- **vs. Postman/newman** — the collection is valuable as a demo, but async
  polling, retry/timeout semantics, conditional flows and rich assertions are
  painful in Postman and trivial in JUnit. The repo is already a Java reactor with
  a surefire/failsafe convention; a Java suite inherits CI, reporting and JaCoCo
  wiring for free. *Recommendation: keep the collection, refresh it as a
  documentation artifact (§9), but automate in Java.*
- **vs. Testcontainers spinning the whole fleet** — 8 services + Kafka + Postgres
  + observability per run is slow and duplicates `docker-compose.yml`. The point
  of this suite is to certify **the real deployed topology**, not a bespoke one.
- **Consequence:** the suite requires a **running compose fleet**. It must
  therefore be **opt-in**, never part of the default `mvn verify` (which must stay
  self-contained — see the roadmap's warning about a native Windows Postgres
  silently satisfying tests). Proposed: a `-Pe2e` profile, failsafe-bound,
  excluded by default.

**Fail-fast preflight.** Before any test runs, assert: gateway healthy, all 8
services healthy, seed data present (flight count > 0), ADMIN bootstrap configured.
Each failure produces a specific remediation message (e.g. "run `scripts/seed/seed.sh`"),
because a suite that fails with a 404 three tests deep teaches nothing.

---

# 4. Identity & Isolation

- **ADMIN**: bootstrapped via `SKYBOOK_BOOTSTRAP_ADMIN_EMAIL`. The suite logs in as
  ADMIN only for back-office assertions (list-all, search, confirm/complete).
- **USER**: a **fresh account per run** (`e2e-<runId>@…`), so runs never collide and
  OWNER-scoping is genuinely exercised. Password satisfies the new complexity policy.
- **A second USER** is created to assert **cross-user isolation** (B reading A's
  booking → 403) as part of certification, not just security testing.
- **No cleanup/teardown of seed data.** Runs are additive and isolated by unique
  identities; the seed set is large enough that this is sustainable. (Alternative —
  DB reset per run — is rejected: it makes runs mutually exclusive and can't run
  against a shared environment.)

---

# 5. The Happy Path (the spine)

One test, asserted end to end, in order:

```
register → login (USER)
  → search flights (pick a seeded flight ≥ 25h out, so check-in is closed initially)
  → quote  (cabin availability + surcharge preview)
  → create booking (DRAFT)  → assert PNR, ownerSubject captured
  → seat hold             → assert held seat + surcharge
  → payment authorize     → capture   → assert invoice
  → [async] booking CONFIRMED         (poll)
  → [async] check-in rows created     (poll — Kafka BookingEvent → checkin)
  → check-in passenger    → boarding pass issued (assert signature/QR present)
  → board                 → assert BOARDED
  → [async] notification email event consumed  (see §10.2)
```

Every step asserts **both** the response *and* the state change it implies.

---

# 6. Failure Matrix (the part that actually proves robustness)

| Scenario | Trigger (real, in-product) | Assert |
|---|---|---|
| **Declined payment** | amount ending `.13` | authorize → DECLINED; booking stays unconfirmed; seat hold not consumed; retry with a good amount succeeds |
| **Duplicate request** | replay `Idempotency-Key` | second call returns the *same* payment, no double charge |
| **Cancellation + refund** | cancel a confirmed booking | fare-type refund rules applied; invoice/breakdown preserved; seat returned to inventory |
| **Expired hold** | see §10.1 | hold released; seat re-sellable; DRAFT booking swept |
| **Service down** | `docker compose stop inventory-service` | booking create → clean 502 (not a 500/hang); circuit breaker opens; **recovers** after restart |
| **Cross-user access** | USER B reads USER A's booking | 403 |
| **Unauthenticated** | no/forged token at the gateway | 401 |
| **Overbooking / double-sell** | two concurrent holds on the same seat | exactly one wins; the other gets a clean conflict |

The service-down and concurrency cases are the ones that most often expose real
defects, and neither is covered by any existing test today.

---

# 7. Observability Certification (closes the known gap)

After a completed journey, query **Tempo** for the trace containing the booking
and assert it spans **gateway → booking → (Kafka) → payment → booking-confirm**.
This is the first real proof of the Kafka-hop trace propagation that
`OBSERVABILITY_MODULE.md` could only verify by mechanism. Tempo is host-published
(`:3200`), so this is directly queryable.

Optionally assert Prometheus counters moved (bookings created, payments captured).

---

# 8. Where It Runs

- **Locally**: `docker compose up -d` → `scripts/seed/seed.sh` → `mvn -Pe2e verify`.
- **In CI**: *proposed* as a separate, non-blocking workflow (`e2e.yml`) on
  workflow_dispatch + nightly — **not** on every PR. Standing up the full fleet per
  PR would add many minutes to every build for a suite whose main value is
  pre-release confidence. See §10.3.

---

# 9. Deliverables

1. `e2e-tests` module: preflight, happy path, failure matrix, trace assertion.
2. A single documented entry point (`scripts/e2e.sh`) that does compose-up → seed →
   run → report, so "prove it works" is genuinely one command.
3. **Refreshed Postman collection** — add the missing check-in/boarding-pass
   requests and fix the now-ADMIN-only requests, so the manual/demo artifact stops
   being misleading.
4. Implementation Notes section (house pattern) recording what the first real
   end-to-end run exposed.

---

# 10. Decisions Settled

**10.1 — Expired holds: e2e-only TTL override.** A `docker-compose.e2e.yml`
override shortens `inventory.hold.ttl` to ~30s (sweep already runs every 60s, so
it is tightened too). The suite therefore certifies the **sweep logic**, which is
byte-identical to production; only the clock differs. Rejected: an ADMIN
"expire now" endpoint (adds production surface purely for testing) and direct DB
manipulation (reaches behind the API, contradicting §1.3's gateway-only rule).
**The override file must be e2e-only and never merged into the default compose.**

**10.2 — Notifications: MailHog.** Added to compose as the SMTP sink; the suite
asserts a **real captured email** via MailHog's HTTP API. This upgrades the
assertion from "an event was published" to "an email actually arrived", which is
what the customer journey actually claims. Notification-service's mail host/port
point at MailHog under the e2e override.

**10.3 — CI: nightly + manual dispatch.** A separate, non-blocking `e2e.yml`
(`schedule` + `workflow_dispatch`). PR feedback stays fast; the suite still gives
pre-release confidence. Explicitly **not** a per-PR gate — standing the full fleet
up on every PR is not worth the minutes.

**10.4 — Concurrency (double-sell) is IN scope for v1.** It is the highest-value
case in the matrix: the seat-locking added during the PR #7 review has never been
exercised under real concurrency, so it is currently certified only by inspection.

---

# 13. Build Order

Each step ends with something demonstrably working, in the project's usual style.

1. **Harness skeleton + preflight.** `e2e-tests` module, `-Pe2e` profile
   (failsafe-bound, excluded from default `mvn verify`), RestAssured + Awaitility.
   Preflight asserts: gateway healthy, 8 services healthy, a **future** flight
   exists, ADMIN bootstrap configured — each with a specific remediation message.
2. **Identity fixtures.** ADMIN login + fresh-USER-per-run factory (complexity-
   compliant password, unique email), plus the second USER for isolation checks.
3. **Happy path spine** (§5) — through to CONFIRMED booking, asserting the async
   hops by polling.
4. **Check-in → boarding pass → board**, completing the journey.
5. **MailHog** into compose (+ e2e override) and the real-email assertion.
6. **Failure matrix part 1** — declined payment (`.13`), duplicate
   `Idempotency-Key`, cancellation + refund rules, cross-user 403, unauthenticated 401.
7. **E2E compose override + expired-hold case** (§10.1).
8. **Service-down case** — stop inventory, assert clean 502 + breaker opens,
   restart, assert recovery.
9. **Double-sell concurrency case** (§10.4) — two concurrent holds on one seat;
   exactly one wins, the other gets a clean conflict.
10. **Tempo trace assertion** (§7) — closes the observability Kafka-hop gap.
11. **`scripts/e2e.sh`** one-command entry point (compose up → seed → run → report).
12. **Refresh the Postman collection** (§9.3) so the manual artifact stops lying.
13. **`e2e.yml`** nightly + dispatch workflow.
14. **Doc → Implemented + Implementation Notes**, recording what the first real
    full run exposed.

---

# 11. Explicitly Out of Scope (v1)

- Load/performance testing (this certifies correctness, not throughput).
- Chaos engineering beyond the single service-down case.
- Frontend/UI testing — there is no frontend yet.
- Kubernetes-environment certification — the k8s branch is still blocked; this
  suite targets compose. It should be written so the base URL is configurable, so
  it can later be pointed at a cluster ingress without a rewrite.

---

# 12. Risks

- **Flaky async assertions** — mitigated by poll-with-timeout (Awaitility), never
  fixed `sleep`s. Timeouts must be generous (CI runners are slow; the resilience
  branch already learned 20s was too tight and 40s was fine).
- **Seed drift** — the seed spans today→+365d. A run a year from now finds no
  future flights. Preflight must assert a *future* flight exists, not just any flight.
- **Suite becomes a maintenance burden** — mitigated by driving only the gateway's
  public contract, so internal refactors don't break it.
- **Two probabilistic unit-test flakes already exist** (`PaymentReferenceGeneratorTest`,
  `BoardingPassNumberGeneratorTest`); they're unrelated but will muddy any "is the
  build green?" signal until fixed.
