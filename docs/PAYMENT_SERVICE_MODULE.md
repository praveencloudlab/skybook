# 💳 SkyBook Payment Service Module — Design

---

## Project Information

| | |
|---|---|
| **Module** | `payment-service` |
| **Sprint** | Sprint 5 |
| **Branch** | `feature/payment-management` (proposed) |
| **Base package** | `com.skybook.praveen.paymentservice` |
| **Port** | `8086` |
| **Database** | `skybook_payment` (PostgreSQL, `ddl-auto: update`) |
| **Status** | **DESIGN — not yet implemented** |

Decisions settled up front (agreed before implementation): **two-phase authorize/capture model**, **v1 payments auto-created by consuming `BookingEvent CREATED`**, **fare-type-based refund rules**.

---

# Table of Contents

1. [Overview](#1-overview)
2. [Architecture](#2-architecture)
3. [Domain Model / Aggregates](#3-domain-model--aggregates)
4. [Enums & State Machines](#4-enums--state-machines)
5. [The Gateway Abstraction](#5-the-gateway-abstraction)
6. [Refund Rules](#6-refund-rules)
7. [Invoices](#7-invoices)
8. [Idempotency](#8-idempotency)
9. [API Endpoints](#9-api-endpoints)
10. [Kafka — Events In and Out](#10-kafka--events-in-and-out)
11. [Cross-Service Integration & the Sprint 6 Flow](#11-cross-service-integration--the-sprint-6-flow)
12. [Domain Services](#12-domain-services)
13. [Shared Code (`skybook-common` additions)](#13-shared-code-skybook-common-additions)
14. [Configuration](#14-configuration)
15. [Deferred / Out of Scope](#15-deferred--out-of-scope)
16. [Known Risks / Open Questions](#16-known-risks--open-questions)
17. [Build Order](#17-build-order)
18. [Testing Plan](#18-testing-plan)

---

# 1. Overview

`payment-service` completes the booking lifecycle: it owns **money movement** for bookings — authorization, capture, cancellation, refunds — plus the transaction ledger and invoices. It replaces the "simulated payment success" currently hardcoded inside booking-service's `confirmBooking`.

What it deliberately does **not** own:

- Bookings → booking-service (referenced by `bookingId` / PNR only).
- Real money — v1 uses a **simulated gateway** behind an interface (§5); Stripe/Adyen/etc. plug in later without touching the rest of the service.
- Notifications → payment events published to Kafka; notification-service may render them later.

Architecture, layering, state-machine pattern, facade philosophy, test style: identical to flight/booking/inventory. Anyone who has read `INVENTORY_SERVICE_MODULE.md` knows how this module works before reading further.

---

# 2. Architecture

```
controller → facade → service (impl) → repository
               │            │
               │            └── domain (state machine, calculators, validators, generator)
               │
               ├── client   (PaymentGatewayClient interface → SimulatedPaymentGateway)
               ├── producer (PaymentEvent → skybook.payment.events)
               └── consumer (BookingEvent ← skybook.booking.events)
```

Package structure as proposed, with two adjustments for consistency:

- **`specification/` dropped for v1** — flight/booking/inventory all use derived queries + in-memory filtering at current volumes; adopting JPA Specifications only here would be inconsistent. Revisit for all services together if search volume demands it.
- **`util/` dropped** — nothing identified that belongs there; utility classes without an owner attract junk. The generators/validators live in `domain/`.

**The facade owns ALL orchestration** — every multi-step flow (authorize → capture → invoice → publish) is a facade method; services never call the gateway, other services, or the producer. Concretely, for capture:

```
PaymentFacade.capture(id)                        [NOT @Transactional]
  1. paymentService.getForCapture(id)            [tx: validates state, returns data]
  2. gateway.capture(...)                        [external I/O - deliberately OUTSIDE any DB transaction]
  3. paymentService.recordCaptureResult(...)     [tx: transition + ledger row + invoice, one commit]
  4. producer.publishPaymentSucceeded/Failed     [after commit - standard after-commit rationale]
```

Gateway calls inside DB transactions would hold connections/locks across network I/O — the rule is stated here so it survives implementation. Services own persistence transactions; domain classes stay pure. The consumer is the one new architectural element relative to inventory — payment-service is the first service that *reacts* to another service's events.

---

# 3. Domain Model / Aggregates

One aggregate root (`Payment`) with an append-only ledger, plus two owned records:

```
Payment (root)
  ├── PaymentTransaction (1..N, append-only — every gateway interaction)
  ├── PaymentHistory     (1..N, append-only — every state change, cascade)
  ├── Refund             (0..N)
  └── Invoice            (0..1, created at capture)
```

All entities extend common `Auditable` (`@Version` included — same optimistic-locking story as inventory).

## 3.1 `Payment` (aggregate root)

| Field | Notes |
|---|---|
| `paymentReference` | Unique, immutable, system-generated — `PAY-2026-K7M4Z9` style (§12). External correlation id. |
| `bookingId` | Reference-only. **Unique** — one payment aggregate per booking (v1; see §8). |
| `bookingReference` | PNR, denormalized for support/debugging and invoice display. |
| `amount`, `currency` | From `BookingEvent.totalFare`/`currency`; currency ISO-4217-validated. |
| `capturedAmount`, `refundedAmount` | Running totals maintained by the service layer. Invariant: `refundedAmount <= capturedAmount <= amount`. |
| `status` | `PaymentStatus`, string-persisted. |
| `method` | `PaymentMethod` — CARD only in v1 (simulated). |
| `idempotencyKey` | Nullable, unique when present (§8). |
| `gatewayReference` | The simulated (later: real) gateway's id for the authorization. |
| `failureReason` | Set when status = FAILED. |
| `transactions`, `history`, `refunds` | Collections; history via cascade (booking/inventory pattern), transactions and refunds saved explicitly. |

## 3.1.1 Aggregate invariants

The business rules the `Payment` aggregate must never violate, and **where each is enforced** — a DB-enforced rule survives buggy code; a code-enforced rule does not:

| Invariant | Enforced by |
|---|---|
| `capturedAmount <= amount` | Service layer (`PaymentValidator`) + state machine (capture only from AUTHORIZED/CAPTURE_FAILED). v1 captures are full-amount, making it structural. |
| `refundedAmount <= capturedAmount` | Service layer (`PaymentValidator`), re-checked on every refund; concurrent refunds serialized by `@Version`. |
| One payment per booking | **DB unique constraint** on `bookingId`. |
| `paymentReference` / `transactionReference` / `refundReference` / `invoiceNumber` / `idempotencyKey` unique | **DB unique constraints.** |
| Invoice exists **iff** payment reached CAPTURED | Created in the same transaction as the CAPTURED transition — never before, never separately. |
| `transactions` ledger is append-only | Convention enforced in the service layer (no update/delete paths exist); rows carry `updatable = false` columns. |
| `history` is append-only | Same mechanism as Booking/Inventory history — written via cascade, no mutation paths. |
| Terminal states (CANCELLED, REFUNDED) are final | State machine — no outgoing transitions. |

JPA integration tests assert the DB-enforced rows directly; the code-enforced ones are covered by service + concurrency tests (§18).

## 3.2 `PaymentTransaction` (append-only ledger)

One row per **gateway interaction, including failures** — the audit trail that survives disputes.

| Field | Notes |
|---|---|
| `transactionReference` | Unique, system-generated (`TXN-2026-A8P1W2` style, §12). |
| `type` | `TransactionType` — AUTHORIZE / CAPTURE / VOID / REFUND. |
| `status` | `TransactionStatus` — SUCCEEDED / FAILED. |
| `amount` | Amount attempted in this interaction. |
| `gatewayReference`, `gatewayResponseCode`, `gatewayMessage` | Whatever the gateway returned — stored verbatim, never parsed for logic. |
| `rawGatewayPayload` | Full raw gateway response (TEXT; jsonb when a real gateway lands). The thing you always wish you had when troubleshooting Stripe. |
| `durationMs` | Gateway round-trip time — feeds avg-authorization-time / gateway-SLA metrics later. |
| `occurredAt` | Ledger ordering. |

Rows are never updated or deleted. A failed capture is a FAILED CAPTURE row, followed (maybe) by a SUCCEEDED CAPTURE row on retry.

## 3.3 `Refund`

| Field | Notes |
|---|---|
| `refundReference` | Unique (`REF-2026-L3Q9XE` style, §12). |
| `amount` | Computed by `RefundCalculator` (§6); partial refunds allowed. |
| `cancellationFee` | The withheld portion — stored explicitly so invoices/emails can show it. |
| `reason` | Free text (e.g. "booking cancelled"). |
| `status` | `RefundStatus` — PENDING / COMPLETED / FAILED. |
| `completedAt` | Set on completion. |

## 3.4 `Invoice`

Immutable once issued (§7).

| Field | Notes |
|---|---|
| `invoiceNumber` | Unique — `INV-{year}-{seq}` from `InvoiceNumberGenerator`. |
| `subtotal`, `taxAmount`, `discount`, `grandTotal` | Future-proofed money breakdown. v1: `taxAmount = discount = 0`, `subtotal = grandTotal = amount` — but the columns exist now, because changing invoices later is the expensive direction. |
| `bookingReference`, `currency`, `issuedAt` | Snapshot at capture time; later refunds do **not** mutate the invoice (credit notes are future work, §15). |

## 3.5 `PaymentHistory`

Same mechanics as `BookingHistory`/`InventoryHistory` (state machine writes in-memory, cascade persists), but with richer provenance for production debugging:

| Field | Notes |
|---|---|
| `historyType` | `PaymentHistoryType`, string enum. |
| `actor` | Who acted: `USER`, `SYSTEM`, `KAFKA`, `SCHEDULER`. String column with a documented vocabulary (not an enum — new actors shouldn't need a schema-adjacent change). |
| `source` | Where it came from: `API`, `BOOKING_EVENT`, `EXPIRY_JOB`, ... |
| `correlationId` | The id that ties the change to its trigger: HTTP request id, `bookingReference` for event-driven changes, job run id. |
| `details`, `changedAt` | As in the sibling services. |

---

# 4. Enums & State Machines

All enum fields `@Enumerated(EnumType.STRING)` — standing rule.

## 4.1 `PaymentStatus` — the core machine (two-phase, split failure states)

Failure is split into `AUTHORIZATION_FAILED` and `CAPTURE_FAILED` rather than a single `FAILED` — they carry different meanings (capture failure leaves a live authorization behind) and therefore have **different legal transitions**, not just different dashboard labels:

| From | Allowed to |
|---|---|
| PENDING | AUTHORIZED, AUTHORIZATION_FAILED, CANCELLED |
| AUTHORIZATION_FAILED | PENDING *(retry authorization)*, CANCELLED |
| AUTHORIZED | CAPTURED, CAPTURE_FAILED, CANCELLED *(= gateway VOID)* |
| CAPTURE_FAILED | CAPTURED *(retry capture — authorization still live)*, CANCELLED *(void)* |
| CAPTURED | PARTIALLY_REFUNDED, REFUNDED |
| PARTIALLY_REFUNDED | PARTIALLY_REFUNDED *(further partials)*, REFUNDED |
| CANCELLED, REFUNDED | — terminal |

Notes: the PARTIALLY_REFUNDED self-loop is legal (multiple partial refunds) — first self-loop in the project; the `EnumMap` handles it naturally but tests must cover it explicitly. `failureReason` on `Payment` carries the gateway's message for both failure states.

## 4.2 `TransactionType` / `TransactionStatus`

`AUTHORIZE, CAPTURE, VOID, REFUND` × `SUCCEEDED, FAILED`. Plain vocabularies, no machine.

## 4.3 `RefundStatus`

PENDING → COMPLETED | FAILED. FAILED → PENDING (retry).

## 4.4 `PaymentMethod`

Full vocabulary from day one — only `CARD` is *implemented*, but the enum is stable and future methods need no schema-adjacent change:

`CARD, UPI, BANK_TRANSFER, APPLE_PAY, GOOGLE_PAY, PAYPAL`

Requests carrying a not-yet-implemented method are rejected by `PaymentValidator` with a clear message (400), not by the enum failing to parse.

## 4.5 `PaymentHistoryType`

`PAYMENT_CREATED, AUTHORIZED, AUTHORIZATION_FAILED, CAPTURED, CAPTURE_FAILED, CANCELLED, REFUND_REQUESTED, REFUND_COMPLETED, REFUND_FAILED`

## 4.6 `PaymentStateMachine`

Identical design to `BookingStateMachine`/`InventoryStateMachine`: `EnumMap` transition tables, mutates entities handed to it, appends `PaymentHistory` in-memory, public `recordHistory` for non-transition events, `IllegalStateException` → 409.

---

# 5. The Gateway Abstraction

The single most important interface in the module:

```java
public interface PaymentGatewayClient {
    GatewayResult authorize(String paymentReference, BigDecimal amount, String currency);
    GatewayResult capture(String gatewayReference, BigDecimal amount);
    GatewayResult voidAuthorization(String gatewayReference);
    GatewayResult refund(String gatewayReference, BigDecimal amount);
}
```

`GatewayResult`: `success`, `gatewayReference`, `responseCode`, `message`, `rawPayload` (the gateway's full raw response, persisted onto the `PaymentTransaction` ledger row), `durationMs` (measured around the call by the client implementation). Everything above this interface (facade, services, domain) is gateway-agnostic — Stripe/Adyen/Worldpay/PayPal/Braintree later means one new implementation class + config, nothing else.

**`SimulatedPaymentGateway` (v1)** — deterministic, so failure paths are testable without mocks:

- Amounts ending in **`.13`** → authorization fails (`SIM_DECLINED`).
- Amounts ending in **`.31`** → capture fails after successful auth (`SIM_CAPTURE_FAILED`).
- Everything else succeeds. References are `SIM-` + UUID.

This convention goes in the README and Postman collection — it makes every failure scenario reproducible by hand.

---

# 6. Refund Rules

**Fare-type based** (settled decision). `RefundCalculator` is pure domain:

| Fare type | Refund | Cancellation fee |
|---|---|---|
| FLEXI | 100% | 0% |
| PREMIUM | 100% | 0% |
| SAVER | 70% | 30% |

- Input: the fare breakdown per passenger (`BookingEvent.passengers[].fareType/fare`) — a mixed-fare booking refunds each passenger line by its own rule; the fee is the sum of withheld portions.
- Refunds only from CAPTURED / PARTIALLY_REFUNDED state; cumulative refunds can never exceed `capturedAmount` (validated by `PaymentValidator`, enforced again by the DB invariant check in the service).
- Fee percentages are configuration (`payment.refund.saver-fee-percent: 30`), not code.

---

# 7. Invoices

- Generated **at capture**, in the same transaction — every captured payment has exactly one invoice.
- `InvoiceNumberGenerator`: `INV-{year}-{six-digit sequence}` backed by a **database sequence** (not max+1 — concurrency-safe). Sequence resets per year is deferred; v1 uses one global sequence with the year prefix for readability.
- Invoices are immutable. Refunds do not alter them (credit notes: §15).
- `GET /api/invoices/{paymentId}` returns the snapshot.

---

# 8. Idempotency

Payments are where duplicate processing costs real money. Three layers:

1. **API idempotency** — `POST /api/payments` accepts an optional `Idempotency-Key` header, stored on `Payment` with a unique constraint. A replay with the same key returns the **original payment (200)**, not a duplicate (nor an error) — standard Stripe semantics.
2. **Consumer idempotency** — the `BookingEvent CREATED` consumer's natural key is `bookingId` (unique column). A redelivered/duplicate event finds the existing payment and does nothing. This makes the consumer safely re-runnable from `earliest`.
3. **Gateway idempotency** — every gateway call carries the `paymentReference`/`transactionReference`, so a retried capture after a timeout can be reconciled against the ledger (v1 simulated gateway is synchronous so this is mostly future-proofing; the ledger design is what matters).

State-machine guards are the backstop: capturing a CAPTURED payment is an invalid transition → 409, regardless of how the duplicate arrived.

---

# 9. API Endpoints

## Payments — `/api/payments`

| Method | Path | Notes |
|---|---|---|
| POST | `/api/payments` | Manual/direct creation (consumer covers the normal path). `Idempotency-Key` honored. 201; replay → 200. |
| GET | `/api/payments/{id}` | 404 if unknown. |
| GET | `/api/payments/reference/{reference}` | Lookup by `PAY-...`. |
| GET | `/api/payments/booking/{bookingId}` | **Added** — booking-service's Sprint 6 lookup; the natural query. |
| PATCH | `/api/payments/{id}/authorize` | **Added** — explicit two-phase step (simulated gateway). PENDING → AUTHORIZED/FAILED. |
| PATCH | `/api/payments/{id}/capture` | AUTHORIZED → CAPTURED; creates the Invoice; publishes `PAYMENT_SUCCEEDED`. |
| PATCH | `/api/payments/{id}/cancel` | PENDING/AUTHORIZED → CANCELLED (VOID if authorized). |
| PATCH | `/api/payments/{id}/refund` | Body: optional passenger-line subset + reason. Computes via `RefundCalculator`, publishes `REFUND_COMPLETED`. |

## Refunds — `/api/refunds`

| Method | Path |
|---|---|
| GET | `/api/refunds` |
| GET | `/api/refunds/{id}` |

## Invoices

| Method | Path |
|---|---|
| GET | `/api/invoices/{paymentId}` |

Error contract identical to inventory (404/409/400, optimistic-lock 409) plus: **422** for gateway declines (`SIM_DECLINED` etc. — the request was valid, the money wasn't) — a deliberate, documented distinction from 409 state conflicts.

---

# 10. Kafka — Events In and Out

## Consumed — `skybook.booking.events`

| Event type | Action |
|---|---|
| `CREATED` | Create PENDING `Payment` (amount = `totalFare`, currency from event, fare breakdown snapshot for refund rules). Idempotent by `bookingId`. |
| `CANCELLED` | If CAPTURED/PARTIALLY_REFUNDED → auto-refund per §6. If PENDING/AUTHORIZED → cancel/void. If already terminal → no-op. |
| `CONFIRMED`, `COMPLETED`, `EXPIRED` | Ignored in v1 (logged). CONFIRMED is *booking's* reaction to payment, not the reverse — see §11. |

The enriched `BookingEvent` (structured fields added during the email work) already carries everything the consumer needs — **no booking-service changes required this sprint.**

## Produced — `skybook.payment.events` (topic already in `KafkaTopics`)

New shared contract `PaymentEvent` with `PaymentEventType`: `PAYMENT_SUCCEEDED` (on capture), `PAYMENT_FAILED` (on auth/capture failure), `PAYMENT_CANCELLED`, `REFUND_COMPLETED`, `REFUND_FAILED`. Fields: type, `paymentReference`, `bookingId`, `bookingReference`, `amount`, `currency`, `refundedAmount`/`cancellationFee` (refund events), `failureReason`, `invoiceNumber` (success), `occurredAt`.

Published by the **facade after commit** — same after-commit rationale as booking/inventory; outbox remains the known upgrade path.

---

# 11. Cross-Service Integration & the Sprint 6 Flow

**v1 (this sprint) — payment-service is self-contained:**

```
booking created ──BookingEvent CREATED──▶ payment PENDING (auto)
client/Postman ──PATCH authorize, capture──▶ CAPTURED + Invoice + PAYMENT_SUCCEEDED event
booking cancelled ──BookingEvent CANCELLED──▶ auto-refund / void
```

Nobody consumes `PAYMENT_SUCCEEDED` yet — it goes to the topic and waits, exactly like booking events waited before the notification consumer existed.

**Sprint 6 (the full loop, explicitly out of scope now):**

```
Booking CREATED ─▶ Payment PENDING ─▶ authorize+capture
     ─▶ PAYMENT_SUCCEEDED ─▶ booking-service consumer confirms the booking
     ─▶ booking CONFIRMED event ─▶ notification email
Inventory: hold on booking creation, confirm on payment success   (the deferred booking↔inventory wiring joins here)
```

Sprint 6 removes booking-service's simulated `confirmBooking` payment and inverts control: payment success *drives* confirmation. That's a booking-service change and belongs there — this module just has to publish the right events, which is why `PaymentEvent`'s shape is a settled decision now.

---

# 12. Domain Services

| Class | Role |
|---|---|
| `PaymentStateMachine` | §4.6. |
| `RefundCalculator` | §6 — pure; per-fare-line refund + fee computation. |
| `PaymentValidator` | Guard clauses: amount > 0, refund ≤ captured remainder, currency present, state preconditions with precise messages. |
| `InvoiceNumberGenerator` | §7 — wraps the DB sequence; the only domain class allowed a repository-ish dependency (a sequence is not business logic to fake). |
| `CurrencyValidator` | ISO-4217 whitelist from config (`payment.supported-currencies: USD,GBP,EUR,INR`). Rejects everything else with 400. |
| `PaymentReferenceGenerator` | **Added** — human-readable references in the PNR philosophy, not UUIDs: `PAY-2026-K7M4Z9`, `TXN-2026-A8P1W2`, `REF-2026-L3Q9XE` (year + 6 chars from the PNR alphabet, ambiguous characters excluded). Uniqueness re-checked on collision, PnrGenerator precedent. Support staff can read these over the phone. |

---

# 13. Shared Code (`skybook-common` additions)

- `event/PaymentEvent`, `event/PaymentEventType` — mirrors `BookingEvent`/`InventoryEvent` style. Additive only.
- `KafkaTopics.PAYMENT_EVENTS` already exists — reused.
- Nothing else — `Auditable`, `ErrorResponse` reused as-is.

---

# 14. Configuration

```yaml
server.port: 8086
spring.datasource.url: jdbc:postgresql://localhost:5432/skybook_payment
spring.kafka.bootstrap-servers: localhost:9092   # consumer group: payment-service

payment:
  supported-currencies: USD,GBP,EUR,INR
  refund:
    saver-fee-percent: 30
  gateway:
    simulated: true        # future: stripe/adyen selection
```

Config classes mirror the fleet: `JpaAuditingConfig`, `KafkaProducerConfig` (PaymentEvent) + consumer factory for BookingEvent (explicit container factories — the two-factory lesson from notification-service applies from day one), `SecurityConfig` permit-all placeholder.

**Create the database before first run:** `CREATE DATABASE skybook_payment;`

---

# 15. Deferred / Out of Scope

- **Webhooks** — real gateways call back asynchronously; the simulated gateway is synchronous, so `POST /api/payments/webhook/{gateway}` is designed-for but not built. Building it against a fake caller proves nothing.
- **Real gateway integration** (Stripe first, most likely) — one `PaymentGatewayClient` impl + webhook handling + secrets management.
- **Credit notes** — invoice-adjusting documents for refunds.
- **Multi-currency conversion / FX** — currencies are validated but never converted; a booking pays in its own currency.
- **Scheduler** — no payment-side TTL job identified for v1 (holds live in inventory). The package exists in the proposal but starts empty; a pending-payment-expiry job is a natural Sprint 6 candidate once booking timeouts are defined.
- **Booking-service changes** — all of §11's Sprint 6 flow.
- **Transactional outbox** — same standing deferral as every other service.

---

# 16. Known Risks / Open Questions

1. **Event-carried amounts** — the payment amount comes from `BookingEvent.totalFare`. If a booking is modified after CREATED (fare change), the payment is stale. v1 accepts this (bookings aren't modifiable today); the fix is an amount re-check against booking-service at authorize time.
2. **Unbounded retry loops** — `AUTHORIZATION_FAILED → PENDING` and `CAPTURE_FAILED → CAPTURED` retries have no attempt limit in the machine; a retry counter/limit is a config-level improvement to consider during implementation.
3. **One payment per booking** — the unique `bookingId` makes split payments (two cards) impossible without schema change. Accepted for v1; the aggregate boundary would survive a join table later.
4. **Refund snapshot** — fare breakdown is snapshotted onto the payment at creation (from the event) so refund rules don't need a booking-service call. Snapshot vs live-lookup is a deliberate trade: refunds compute against what was paid, which is arguably *more* correct.
5. **Sequence-backed invoice numbers under `create-drop` tests** — Testcontainers tests recreate the sequence each run; fine, but the JPA tests must not assert absolute numbers.

---

# 17. Build Order

Same discipline (compile between steps, commit on green):

1. Module scaffold (pom, app class, yml, parent registration) — port 8086
2. Enums (all 6)
3. Entities: `Payment` → `PaymentTransaction` → `Refund` → `Invoice` → `PaymentHistory` (+ collections)
4. Repositories
5. Response DTOs + mappers, then request DTOs
6. Domain: state machine → validators → `RefundCalculator` → generators
7. Exceptions + handler (incl. the 422 gateway-decline mapping)
8. `PaymentGatewayClient` + `SimulatedPaymentGateway`
9. Services: `PaymentService` → `RefundService` → `InvoiceService`
10. Facade + producer
11. `BookingEventConsumer` (payment side)
12. Controllers (3)
13. Config
14. Tests per §18, then docs/report/Postman updates

# 18. Testing Plan

Mirrors the inventory standard: domain unit tests (golden transition tables — incl. the PARTIALLY_REFUNDED self-loop; `RefundCalculator` per-fare-line cases incl. mixed bookings; validators), service tests (repos mocked, domain + `SimulatedPaymentGateway` real — the `.13`/`.31` conventions make failure paths mock-free), JPA integration (Testcontainers; uniqueness of references/bookingId/idempotencyKey, ledger append-only, sequence generation), consumer tests (duplicate `CREATED` event is a no-op; `CANCELLED` triggers refund), WebMvc (all controllers, 422 mapping, `Idempotency-Key` replay → 200 with original body), full-stack API+Kafka IT (consume `PAYMENT_SUCCEEDED` off a real broker), concurrency (double-capture race → exactly one CAPTURED + one 409; concurrent refunds can't exceed `capturedAmount`), JaCoCo + Sonar + OpenAPI + Postman folder.

---

*Sprint 5 design — review, adjust, then implementation starts at build-order step 1.*
