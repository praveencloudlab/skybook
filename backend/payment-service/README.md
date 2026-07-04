# payment-service

Completes the booking lifecycle: **money movement** for SkyBook bookings — two-phase authorize/capture against a pluggable gateway, refunds with fare-type rules, an append-only transaction ledger, sequence-backed invoices, and payment events for the rest of the platform.

Full design + implementation notes: [`docs/PAYMENT_SERVICE_MODULE.md`](../../docs/PAYMENT_SERVICE_MODULE.md) · Test report: [`docs/payment-service-test-report.html`](../../docs/payment-service-test-report.html)

## Quick facts

| | |
|---|---|
| Port | `8086` |
| Database | PostgreSQL `skybook_payment` (`ddl-auto: update` + one sequence created at startup) |
| Kafka in | consumes `BookingEvent` from `skybook.booking.events` (group `payment-service`) |
| Kafka out | publishes `PaymentEvent` to `skybook.payment.events` |
| Gateway | `SimulatedPaymentGateway` behind the `PaymentGatewayClient` seam — Stripe/Adyen plug in later as one class |
| Swagger | http://localhost:8086/swagger-ui.html |

## The simulated gateway's magic amounts

Deterministic failure testing, no mocks needed:

| Amount ends in | Effect |
|---|---|
| `.13` | authorization **declined** (`SIM_DECLINED`) → 422, payment `AUTHORIZATION_FAILED` |
| `.31` | authorization fine, **capture fails** (`SIM_CAPTURE_FAILED`) → 422, payment `CAPTURE_FAILED` |
| anything else | succeeds |

## Lifecycle

```
BookingEvent CREATED ──▶ PENDING ──authorize──▶ AUTHORIZED ──capture──▶ CAPTURED (+ Invoice, + PAYMENT_SUCCEEDED)
                            │                        │                      │
                            ▼                        ▼                      ▼ refund (fare-type rules)
                    AUTHORIZATION_FAILED       CAPTURE_FAILED      PARTIALLY_REFUNDED ⟲ ──▶ REFUNDED
                     (retry → PENDING)        (retry → CAPTURED)
BookingEvent CANCELLED ──▶ refund if captured, cancel/void otherwise
```

Refund rules: FLEXI/PREMIUM 100%, SAVER keeps a 30% fee (`payment.refund.saver-fee-percent`). Mixed-fare bookings refund per line from the fare snapshot taken at creation.

## Idempotency

- `POST /api/payments` honors an `Idempotency-Key` header — replay returns the original (200, not 201, not an error)
- One payment per booking — DB unique constraint
- Duplicate/replayed booking events are no-ops
- Terminal states + `@Version` + DB constraints backstop everything else (the double-capture race test watches the `invoices` unique constraint stop the losing thread)

## API surface

`/api/payments`: POST (create), GET by id / `reference/{ref}` / `booking/{id}` / `{id}/history`, PATCH `{id}/authorize|capture|cancel|refund` · `/api/refunds`: GET list/by id · `/api/invoices/{paymentId}`: GET (404 until captured).

Errors: 400 validation, 404 not-found, 409 conflicts (state, duplicates, optimistic lock), **422 gateway declines**.

Ready-made requests: the **Payments** folder in [`docs/skybook.postman_collection.json`](../../docs/skybook.postman_collection.json).

## Run & test

Prereqs: Java 21, `CREATE DATABASE skybook_payment;`, Kafka on 9092. Then `mvn spring-boot:run -pl payment-service`.

`mvn test -pl payment-service -am` — 111 tests; the JPA/integration layers need Docker (Testcontainers PostgreSQL + Kafka) and skip without it. Coverage: `target/site/jacoco/index.html`.

## Sprint 6 (not yet wired)

Nobody consumes `PAYMENT_SUCCEEDED` yet. Sprint 6: booking-service confirms bookings off it (retiring the simulated payment in `confirmBooking`), and inventory holds join the booking flow.
