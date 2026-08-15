# SKB-DOC-02 — System Architecture (High-Level Design)

| | |
|---|---|
| **Document ID** | SKB-DOC-02 |
| **Version** | 1.0 |
| **Status** | Baselined |
| **Owner** | Platform Engineering |
| **Effective date** | 2026-08-01 |
| **Related** | SKB-DOC-04 (interfaces), SKB-DOC-05 (data), SKB-DOC-06 (security), `docs/ARCHITECTURE.md` (narrative) |

## 1. Architectural style and the three rules

SkyBook is a synchronous-command / asynchronous-event microservice system.
Three rules generate most of the design; when in doubt, apply them:

1. **One writer per fact.** Every piece of state has exactly one owning
   service; everyone else holds read-models fed by events or asks
   synchronously. (Seat state → inventory. Money → payment. Check-in truth →
   checkin. Booking composition → booking.)
2. **Commands are synchronous, facts are asynchronous.** A user-facing
   operation that must succeed-or-fail-now calls over HTTP through the
   gateway or Feign; everything that happened is announced on Kafka and
   consumed idempotently. No business operation fails because a consumer of
   its news is down.
3. **The caller compensates.** A multi-service operation is a saga owned by
   the initiating facade: it takes resources in a safe order and undoes its
   own partial work on failure (hold-seats → pay → reserve; release on any
   failure). There is no distributed transaction anywhere.

## 2. System context

```
                          ┌───────────────────────────────┐
  Passenger / Guest ──────►  React SPA (frontend, nginx)  │
  Back-office agent       │  serves UI, proxies /api      │
                          └──────────────┬────────────────┘
                                         │ HTTP /api/**
                          ┌──────────────▼────────────────┐
                          │  api-gateway :8080            │
                          │  JWT verify, routing, CORS    │
                          └──┬────┬────┬────┬────┬────┬───┘
             ┌───────────────┘    │    │    │    │    └──────────────┐
        ┌────▼────┐  ┌───────▼──────┐ ┌▼────────┐ ┌──────▼───┐ ┌────▼─────┐
        │  auth   │  │   flight     │ │ booking │ │inventory │ │ payment  │
        └────┬────┘  └───────┬──────┘ └┬───┬────┘ └────┬─────┘ └────┬─────┘
             │               │         │   │           │            │
             │          ┌────▼───┐ ┌───▼───▼───┐       │            │
             │          │checkin │ │notification│      │            │
             │          └────┬───┘ └───────────┘       │            │
             └───────────────┴───────── Kafka events ──┴────────────┘
        PostgreSQL: one database per service · Observability: Prom/Grafana/Loki/Tempo
```

External integrations: Google (SSO), SMTP (Gmail app-password or Mailpit),
the simulated card gateway (in-process). Nothing else leaves the boundary.

## 3. Service catalogue

| Service | Owns (single writer of) | Key collaborators |
|---|---|---|
| **api-gateway** | Nothing (stateless edge): token verification, route table, public-path allow-list, CORS | All services |
| **auth-service** | Identities, credentials, roles, service-client registry, profile preferences, password reset, SSO | Issues every token in the system |
| **flight-service** | Flights, schedules, aircraft/cabin configs, terminal assignments (TerminalPolicy), airline reference data | Booking/checkin validate flights against it |
| **inventory-service** | Seat maps, seat state (AVAILABLE/HELD/RESERVED), seat pricing tiers, atomic auto-hold | Booking and checkin drive it; it never calls out |
| **booking-service** | Bookings, segments, passengers, tickets/coupons, fare calculation, cancellation policy, fare watches | Orchestrates the booking saga; the largest facade |
| **payment-service** | Payments, refunds, invoices, gateway simulation, refund calculator | Reacts to booking events; publishes payment events |
| **checkin-service** | Check-in records, boarding passes (issue/reissue/revoke), gates/boarding groups, no-show sweep, baggage, manifests | Reacts to booking events; booking mirrors its facts |
| **notification-service** | Nothing durable: renders and sends e-mail (HTML + PDF attachments) from events | Pure consumer |
| **frontend** | The SPA and its nginx (serves static assets, proxies `/api` to the gateway, strips `Origin` for domain-agnostic deployment) | — |

Supporting: PostgreSQL (one DB per service — SKB-DOC-05), Kafka (KRaft),
Mailpit (dev mail), Prometheus/Grafana/Loki/Promtail/Tempo/otel-agent
(NFR-03).

## 4. Canonical flows

Full sequence detail lives in the module designs; these are the shapes every
developer must know.

### 4.1 Booking saga (`BookingFacade.createBooking`)

```
validate flights (bookable, chronology, through-ticket layovers)
→ createDraftBooking            [booking tx: DRAFT rows, no money]
→ per-passenger seat holds       [inventory, outside any tx; AUTO or MANUAL]
→ finalizeSeatAssignments        [booking tx: seats+surcharges+totals, DRAFT→CREATED]
→ publish CREATED                [payment-service creates PENDING payment]
   … user pays: authorize → capture …
→ PAYMENT_SUCCEEDED consumed     [booking: CONFIRMED, tickets issued]
→ holds converted to reservations; publish CONFIRMED
   [checkin-service creates per-segment check-in records]
Failure at any step: release every hold taken, cancel the draft, rethrow.
```

### 4.2 Cancellation (`BookingFacade.cancelBooking` / partial paths)

```
assess: ADMIN? → bypass. Unpaid? → free. Else time-tier vs earliest
        upcoming departure (CancellationPolicy; Premium waiver tier per line)
→ booking tx: rows cancelled, coupons REFUNDED/CANCELLED, statuses derived
→ release seats (hold + reservation, quiet, idempotent) — the seat actually
  held, which may differ from the seat originally booked
→ publish CANCELLED (whole) or PARTIALLY_CANCELLED (surviving booking)
  carrying refundTierPercent + refundBreakdown + cancelled row ids
→ payment-service refunds exactly the quoted lines; checkin-service cancels
  the affected check-ins and revokes passes; notification mails the amount
```

### 4.3 Check-in

```
CONFIRMED event → checkin creates one record per passenger per segment
window opens T-24h → passenger (or guest session) checks in
→ seat confirmed/changed within entitlement (inventory reserve-new-first)
→ boarding pass issued: signed QR token, terminals, gate, group
→ BOARDING_PASS_GENERATED event → booking mirrors status+seat; notification
  e-mails the pass PDF
gate close T-45m → no-show sweep; departure + sweep → coupons FLOWN
```

## 5. Technology baseline

| Concern | Choice | Notes |
|---|---|---|
| Language / runtime | Java 21, Spring Boot 3.x | Records for DTOs; constructor injection only |
| Frontend | React 18 + Vite + TypeScript + Tailwind | Vitest for tests |
| Persistence | PostgreSQL 16, Spring Data JPA, Flyway | Migrations are append-only (SKB-DOC-05 §4) |
| Messaging | Kafka (KRaft, no ZooKeeper) | Topics in SKB-DOC-04 §5 |
| Sync calls | OpenFeign + Resilience4j (timeout, CB, bulkhead, read-only retry) | `RESILIENCE_MODULE.md` |
| Security | RS256 JWT, per-service client credentials | SKB-DOC-06 |
| Observability | Micrometer→Prometheus, logstash-logback→Loki, OTel agent→Tempo, Grafana | `OBSERVABILITY_MODULE.md` |
| Build & CI | Maven multi-module reactor; GitHub Actions; SonarCloud gate; 8-way Docker build matrix | `CI_CD_MODULE.md` |
| Packaging | Docker Compose (dev = prod topology; prod adds the Caddy TLS overlay) | `docs/ENVIRONMENTS.md`, `docker-compose.prod.yml` |

## 6. Deployment views

- **Developer laptop:** `docker compose up --build` with `.env`; every port
  published on localhost (gateway 8080, frontend 3000, Grafana 3001,
  Mailpit 8025, Prometheus 9090, Loki 3100, Tempo 3200).
- **Production VM:** same compose plus `docker-compose.prod.yml`: Caddy owns
  80/443 with automatic TLS for `SKYBOOK_DOMAIN`; every other published port
  rebinds to 127.0.0.1 (SSH-tunnel access only). Bootstrap:
  `docs/DEPLOY_ORACLE.md`; updates via the Promote pipeline
  (`docs/ENVIRONMENTS.md`).
- **Kubernetes:** complete manifest set exists on `feature/kubernetes`
  (frozen design, not merged); compose remains the production topology.

## 7. Architectural decisions

Decisions with lasting consequences are recorded per module in the design
docs' "decision" sections; the standing ones every developer must not
re-litigate:

1. Database-per-service; no cross-service joins, ever (SKB-DOC-05 §2).
2. Events are facts, not commands: no service tells another what to do over
   Kafka; it announces what happened.
3. Deprecations run one release as parallel mirrors before removal (e.g. the
   flat `BookingEvent` fields and `bookings.flight_id`, retired per
   ROUND_TRIP step 8).
4. New enum values on shared events require all consumers deployed before or
   with the first producer (SKB-DOC-04 §5.4).
5. Money amounts are `BigDecimal` end-to-end with explicit rounding
   (HALF_UP, scale 2) at policy boundaries only.
6. The frontend never computes an authoritative number: every price, refund,
   or window it displays is served or re-checked by a service.
