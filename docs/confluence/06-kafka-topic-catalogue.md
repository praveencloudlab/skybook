# Kafka Topic Catalogue

This page is the reference catalogue of every Kafka topic in the SkyBook platform: who produces it, who consumes it, the payload contract, and the delivery-semantics guarantees each consumer relies on. It is written for backend engineers adding or changing event flows, and for anyone triaging a stuck consumer or a dead-lettered record. Topic names are compile-time constants in `backend/skybook-common/src/main/java/com/skybook/praveen/common/constants/KafkaTopics.java`; payload classes and event-type enums live in `backend/skybook-common/src/main/java/com/skybook/praveen/common/event/`.

## Broker and conventions

- Single-broker KRaft cluster (`apache/kafka:3.9.0`) in the docker compose stack, internal-only: services reach it as `kafka:9092` on the compose network; no host port is published. All replication factors are 1.
- Topics are broker-auto-created, single-partition. Nothing provisions topics explicitly today; the DLT partition invariant below must be preserved if that ever changes.
- Naming convention: `skybook.<domain>.events`. One topic per owning domain; event subtypes are discriminated by a `type` enum field inside the payload, not by separate topics.
- Payloads are JSON (spring-kafka `JsonSerializer` / `JsonDeserializer`), trusted packages `com.skybook.praveen.common.*`.
- Every producer publishes **after** the owning database transaction has committed (facade calls the producer after the `@Transactional` service method returns). Sends are async but not fire-and-forget: a broker-side failure logs at ERROR into the centralized logging pipeline (`RESILIENCE_MODULE.md` §10).

## Topic catalogue

| Topic | Producer(s) | Consumer(s) (group) | Event payload class | Event types enum values | Purpose |
|---|---|---|---|---|---|
| `skybook.email.events` | auth-service (`EmailEventProducer`) | notification-service (`notification-service`) | `EmailEvent` | `EmailType`: `REGISTRATION_SUCCESS`, `FORGOT_PASSWORD`, `EMAIL_VERIFICATION` | Account-lifecycle emails: registration welcome, password reset, and OTP email verification. Payload carries pre-composed `to`/`subject`/`body`. |
| `skybook.booking.events` | booking-service (`BookingEventProducer`) | payment-service (`payment-service`), checkin-service (`checkin-service`), notification-service (`notification-service`) | `BookingEvent` | `BookingEventType`: `CREATED`, `CONFIRMED`, `CANCELLED`, `PARTIALLY_CANCELLED`, `EXPIRED`, `COMPLETED`, `FARE_ALERT` | Booking lifecycle fan-out: payment-service creates/refunds payments, checkin-service creates or cascade-cancels check-ins, notification-service renders booking emails (HTML + ticket PDF on `CONFIRMED`). `FARE_ALERT` is a fare-watch email only; every other consumer ignores it by type. |
| `skybook.payment.events` | payment-service (`PaymentEventProducer`) | booking-service (`booking-service`) | `PaymentEvent` | `PaymentEventType`: `PAYMENT_SUCCEEDED`, `PAYMENT_FAILED`, `PAYMENT_CANCELLED`, `REFUND_COMPLETED`, `REFUND_FAILED` | Closes the payment saga: booking-service confirms the booking off `PAYMENT_SUCCEEDED` (converting seat holds to reservations, which triggers the `CONFIRMED` booking event). `PAYMENT_FAILED` is logged; the booking stays `CREATED` and holds expire via TTL. |
| `skybook.checkin.events` | checkin-service (`CheckInEventProducer`) | booking-service (`booking-service`), notification-service (`notification-service`) | `CheckInEvent` | `CheckInEventType`: `PASSENGER_CHECKED_IN`, `BOARDING_PASS_GENERATED`, `PASSENGER_BOARDED`, `PASSENGER_NO_SHOW`, `PASSENGER_CHECKIN_CANCELLED` | Check-in lifecycle: booking-service mirrors per-passenger status onto `BookingPassenger.checkInStatus` (a denormalized read-model), notification-service emails the boarding pass. Boarding-pass re-sends reuse `BOARDING_PASS_GENERATED` with `resendId` + `requestedBy` set. |
| `skybook.inventory.events` | inventory-service (`InventoryEventProducer`) | none yet (audit/integration stream; the payload javadoc earmarks booking-service and potentially notification-service as future consumers) | `InventoryEvent` | `InventoryEventType`: `INVENTORY_CREATED`, `SEAT_HELD`, `SEAT_RELEASED`, `HOLD_EXPIRED`, `SEAT_RESERVED`, `RESERVATION_CANCELLED` | Seat-inventory state changes (holds, releases, reservations, cancellations). Note: `HOLD_EXPIRED` exists in the enum but has no publisher today - the producer exposes created/held/released/reserved/cancelled publishes only. |
| `skybook.flight.events` | none | none | - | - | Reserved constant in `KafkaTopics`; no producer or consumer exists yet. |

Consumer group naming is one group per service, named after the service (`payment-service`, `booking-service`, `checkin-service`, `notification-service`), so each service gets every record exactly once per group while remaining horizontally scalable.

## Per-consumer behavior

| Consumer | Topic(s) | Acts on | Ignores / notes |
|---|---|---|---|
| payment-service `BookingEventConsumer` | `skybook.booking.events` | `CREATED` (create PENDING payment), `CANCELLED` (refund/void), `PARTIALLY_CANCELLED` (refund exactly the cancelled fare lines, tier-scaled) | `CONFIRMED` etc. ignored - confirmation is booking's reaction to payment, not the reverse. Zero-refund tier (`refundTierPercent = 0`) deliberately creates no refund. |
| checkin-service `BookingEventConsumer` | `skybook.booking.events` | `CONFIRMED` (one CheckIn per passenger, per segment on round trips), `CANCELLED` (cascade-cancel), `PARTIALLY_CANCELLED` (close the named passengers' check-ins) | `CREATED`/`EXPIRED`/`COMPLETED` ignored. Deliberately does not consume `PaymentEvent` - by the time `CONFIRMED` exists, payment already succeeded. |
| notification-service `BookingEventConsumer` | `skybook.booking.events` | All types with a contact email - HTML template when structured fields present, plain-text fallback otherwise; QR on `CREATED`/`CONFIRMED`/`COMPLETED`, ticket PDF on `CONFIRMED` only | Skips events with no contact email. |
| notification-service `CheckInEventConsumer` | `skybook.checkin.events` | `BOARDING_PASS_GENERATED` only (it alone carries pass number/token/gate/boarding time) | All other check-in types ignored - acting on `PASSENGER_CHECKED_IN` too would send a duplicate, mostly-blank email. |
| notification-service `EmailEventConsumer` | `skybook.email.events` | Every `EmailEvent` (sends as-is) | - |
| booking-service `PaymentEventConsumer` | `skybook.payment.events` | `PAYMENT_SUCCEEDED` (confirm booking), `PAYMENT_FAILED` (log; holds expire via TTL) | Failures are logged, not rethrown - a booking-side bug must not poison the payment topic; the payment ledger stays the reconciliation source of truth. |
| booking-service `CheckInEventConsumer` | `skybook.checkin.events` | All types - mirrored to `BookingPassenger.checkInStatus` (`CHECKED_IN`/`BOARDED`/`NO_SHOW`/`CLOSED`); `BOARDING_PASS_GENERATED` also mirrors seat changes from reissued passes | Failures logged, not rethrown; checkin-service remains the source of truth and the mirror catches up on the next event. |

## Delivery semantics

The platform is **at-least-once** end to end; every consumer is written to tolerate redelivery and replay.

**Error handling and dead-lettering.** All four consuming services share the same pattern (payment-service `KafkaConfig` is the canonical copy; booking-, checkin- and notification-service reference it):

- `ErrorHandlingDeserializer` wraps the JSON deserializer as a poison-pill guard - a malformed record fails into the error handler instead of wedging the consumer in an infinite retry loop.
- `DefaultErrorHandler` with `ExponentialBackOffWithMaxRetries(2)`: attempt 1 → 1s → attempt 2 → 2s → attempt 3, then dead-letter. This replaces spring-kafka's default of 10 zero-interval retries followed by silently discarding the message.
- `DeadLetterPublishingRecoverer` publishes failed records to `<source-topic>.DLT` (e.g. `skybook.booking.events.DLT`) with two templates: processing failures carry the JSON-serialized event, deserialization failures carry the original raw bytes recovered from the deserializer header. DLT records include exception metadata headers (`kafka_dlt-exception-message`, stacktrace, original topic/partition/offset).
- **Partition invariant:** the default resolver publishes to the DLT at the *same partition number*, so a DLT must have at least as many partitions as its source topic. Trivially true while all topics are auto-created single-partition; must be preserved if topics are ever provisioned explicitly.

**Idempotency and replay tolerance** (details in `IDEMPOTENCY_MODULE.md`):

- payment-service: payment creation from `CREATED` is idempotent by `bookingId`. Refunds are keyed by a deterministic *cause* derived from the event - `cancel:<bookingId>` for whole-booking cancels, `partial:<bookingId>:<sorted cancelled row ids>` for partial cancels - guarded by a unique index, so a redelivered event names the same cause and the second refund insert is refused (caught and logged as "redelivery, nothing to do").
- checkin-service: check-in creation is idempotent by `bookingPassengerId` (unique constraint), so a redelivered `CONFIRMED` event is a no-op.
- booking-service: the check-in mirror treats an already-matching status as a normal no-op.
- notification-service: `auto-offset-reset: earliest` with no dedup guard beyond "send the email again" - a replayed event re-sends the email (see `NOTIFICATION_SERVICE_MODULE.md` §12). Boarding-pass re-sends carry a `resendId` UUID intended as a consumer-side idempotency key once the transactional-outbox increment lands.

**Schema evolution rules** (enforced by convention in the payload javadoc):

- New payload fields are additive and nullable; consumers must fall back gracefully when they are absent, because old events replay forever.
- New enum constants (`FARE_ALERT`, `PARTIALLY_CANCELLED` set the precedent): deploy **all consumers of the topic before (or together with) the first producer** of the new type - an unknown constant fails deserialization.
- `BookingEvent.segments` (round trips) is preferred over the deprecated top-level flight fields + flat passenger list, which are kept exactly one release as a segment-0 mirror for old consumers and replayed old events.

## Replaying events

Replay is a deliberate, manual operation in this system - there is no automatic DLT re-consumer (`RESILIENCE_MODULE.md` §9: "replay is a human decision at this maturity stage"; admin replay tooling is deferred per §12). Two replay shapes exist:

1. **Offset replay.** Every consumer group uses `auto-offset-reset: earliest`, and the consumers above are idempotent or no-op on redelivery, so resetting a group's offsets (or re-producing a stored event onto its topic) safely re-drives processing. The known-safe, operationally used example: re-producing a booking's `CONFIRMED` event onto `skybook.booking.events` re-renders and re-sends the ticket email without touching payments or check-ins (both dedupe as described above).
2. **DLT replay.** Dead-lettered records sit in `<source-topic>.DLT` as a durable holding pen. Each carries the original record plus `kafka_dlt-*` headers (exception message, stacktrace, original topic/partition/offset) - enough to diagnose and manually re-publish to the source topic with `kafka-console-consumer`/`kafka-console-producer` from inside the broker container.

Step-by-step procedures (compose exec commands, offset-reset syntax, and the prod VM specifics) belong to the operations runbook page in this space (docs/confluence/); the underlying design rationale is in the repo at `docs/RESILIENCE_MODULE.md` (§9-§12) and `docs/IDEMPOTENCY_MODULE.md`.
