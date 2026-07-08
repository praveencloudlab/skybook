# 📧 SkyBook Notification Service Module — Design

---

## Project Information

| | |
|---|---|
| **Module** | `notification-service` |
| **Branch** | `feature/booking-payment-integration` (email work landed alongside Sprint 6) |
| **Base package** | `com.skybook.praveen.notificationservice` |
| **Port** | `8085` |
| **Database** | **None.** Stateless consumer — see §3 for why this deviates from `ARCHITECTURE.md`'s planned `skybook_notification` schema. |
| **Status** | Implemented and wired end-to-end (Kafka → HTML email with inline QR). Automated test coverage is effectively zero — see §8. |

Consumes two independent Kafka event streams and turns each into an email via Gmail SMTP. No REST API of its own, no persistence, no outbound calls to other services.

---

# Table of Contents

1. [Overview](#1-overview)
2. [Architecture](#2-architecture)
3. [Database](#3-database)
4. [Event Contracts](#4-event-contracts)
5. [Consumers](#5-consumers)
6. [Email Rendering — `BookingEmailTemplate`](#6-email-rendering--bookingemailtemplate)
7. [Email Delivery — `EmailService`](#7-email-delivery--emailservice)
8. [Testing](#8-testing)
9. [Configuration](#9-configuration)
10. [Functional Requirements](#10-functional-requirements)
11. [Non-Functional Notes](#11-non-functional-notes)
12. [Known Risks / Open Questions](#12-known-risks--open-questions)
13. [Deferred / Out of Scope](#13-deferred--out-of-scope)
14. [Operational Notes](#14-operational-notes)

---

# 1. Overview

`notification-service` is the fleet's only email sender. It owns no business data — it reacts to events published by other services and renders/sends emails. Two unrelated flows share the process:

| Flow | Trigger | Producer | Output |
|---|---|---|---|
| **Auth emails** | User registers | auth-service (`EmailEvent`) | Plain-text email (registration success today; forgot-password type exists but is unused) |
| **Booking emails** | Booking created/confirmed/cancelled | booking-service (`BookingEvent`) | Rich HTML email with route card, passenger table, and inline QR code |

The two flows are deliberately independent — different Kafka topics, different event shapes, different `KafkaListener` methods, no shared code beyond `EmailService`'s send primitives. `ARCHITECTURE.md` (§2) originally scoped this service to also cover payment updates and check-in reminders; only the auth and booking flows are implemented today (payment-service and a future checkin-service don't yet publish anything this service consumes).

---

# 2. Architecture

```
                 skybook.email.events (EmailEvent)
auth-service ───────────────────────────────────▶  EmailEventConsumer ──▶ EmailService.sendEmail()  (plain text)

                 skybook.booking.events (BookingEvent)
booking-service ─────────────────────────────────▶  BookingEventConsumer ──▶ BookingEmailTemplate.render()
                                                                          ──▶ QrCodeGenerator.generatePng()
                                                                          ──▶ EmailService.sendHtmlEmail()  (HTML + inline QR)
```

No controller, no facade, no service layer beyond `EmailService`/`QrCodeGenerator` — the two consumers *are* the orchestration layer. This is intentionally thinner than booking/payment/inventory's controller→facade→service→repository stack because there's no persistence and no multi-step transaction to coordinate; each consumer method does render-then-send in one pass.

Two `ConsumerFactory`/`ConcurrentKafkaListenerContainerFactory` bean pairs exist in `KafkaConfig` (one typed to `EmailEvent`, one to `BookingEvent`) because a single `JsonDeserializer` can only target one default type. Once a second `ConsumerFactory` bean appears, Spring Boot stops auto-creating the default `kafkaListenerContainerFactory`, so both are declared explicitly and each `@KafkaListener` names its factory (`BookingEventConsumer` via `containerFactory = "bookingEventContainerFactory"`; `EmailEventConsumer` uses the now-explicit default).

---

# 3. Database

**None.** This service has no `spring.datasource.*` configuration, no JPA, no repository layer — confirmed absent from `application.yml`/`application.properties` and `pom.xml` (no `spring-boot-starter-data-jpa`, no `postgresql` driver).

This is a deliberate deviation from `ARCHITECTURE.md` §4, which reserves a `skybook_notification` database for "notification logs, templates if needed." As built, the service is fully stateless:

- **No send log.** A successful send is only visible via `System.out.println` (`EmailService`, lines 33/65) and the consumer's `log.info`. There is no queryable record of what was sent, to whom, or when.
- **No delivery/retry tracking.** A Kafka consumer failure (e.g. SMTP timeout) throws, and Spring Kafka's default error handling retries/logs per its container defaults — there's no dead-letter table or `notification_status` row to inspect afterwards.
- **No template storage.** `BookingEmailTemplate` is Java code (inline HTML via text blocks), not a DB-backed or file-backed template — matches the class-level Javadoc's stated rationale ("no template engine dependency; email clients ignore external stylesheets anyway").

Adding a `skybook_notification` database (a `NotificationLog` table: recipient, type, status, event correlation id, sent-at, failure reason) is the natural next step if delivery auditing becomes a requirement — see §13.

---

# 4. Event Contracts

Both event types live in `skybook-common` (`com.skybook.praveen.common.event`), additive-only so older/newer producers and consumers stay compatible.

## 4.1 `EmailEvent` (auth flow)

| Field | Notes |
|---|---|
| `to`, `subject`, `body` | Fully composed by the producer (auth-service) — this service does no rendering for this flow. |
| `type` | `EmailType` — `REGISTRATION_SUCCESS` (used by `AuthService`), `FORGOT_PASSWORD` (enum value exists, no producer wires it yet). |

Topic: `skybook.email.events` (`KafkaTopics.EMAIL_EVENTS`).

## 4.2 `BookingEvent` (booking flow)

| Field | Notes |
|---|---|
| `type` | `BookingEventType` — `CREATED`, `CONFIRMED`, `CANCELLED`, `EXPIRED`, `COMPLETED`. Drives the status badge color/text and whether a QR is attached (§5.2). |
| `bookingReference`, `contactEmail`, `contactName` | PNR + recipient. Missing `contactEmail` → event is dropped (logged, not retried). |
| `subject`, `message` | Producer-composed plain-text fallback — used verbatim when `passengers` is null/empty (lean/legacy events skip the HTML template entirely). |
| `bookingId`, `bookingStatus`, `flightId`, `bookingDate` | Structured detail, nullable. |
| `flightNumber`, `originAirportCode`, `destinationAirportCode`, `departureTime`, `arrivalTime` | **Flight context — nullable, populated best-effort by booking-service.** The route card (§6.2) only renders when *both* airport codes are present; older events or a flight-service outage at publish time simply omit the card. This was the root cause of the "route not showing" issue this doc follows on from — booking-service was running a stale build that predated the code that populates these fields. |
| `passengers` | `List<BookingEventPassenger>` — name, seat, class, fare type, fare, check-in status. Drives the passenger table and whether the HTML template is used at all. |
| `totalFare`, `currency`, `paymentStatus` | Payment summary line. |

Topic: `skybook.booking.events` (`KafkaTopics.BOOKING_EVENTS`), consumer group `notification-service`, `auto-offset-reset: earliest` (so a restarted consumer replays anything it missed while down — no dedup/idempotency guard beyond "send the email again," see §12).

---

# 5. Consumers

## 5.1 `EmailEventConsumer`

```java
@KafkaListener(topics = "${skybook.kafka.topics.email-events}")
public void consume(EmailEvent event) {
    emailService.sendEmail(event);   // plain text, no template
}
```

No branching, no validation beyond what `EmailService`/Jakarta Mail does implicitly. A blank `to` would fail at send time inside `JavaMailSender`, not before.

## 5.2 `BookingEventConsumer`

```java
if (event.getContactEmail() == null || blank) → log + drop
if (passengers present)
    includeQr = type in {CREATED, CONFIRMED, COMPLETED}   // not CANCELLED/EXPIRED
    html = BookingEmailTemplate.render(event, includeQr)
    includeQr ? sendHtmlEmail(..., qrCid, qrPng) : sendHtmlEmail(...)
else
    sendEmail(contactEmail, subject, message)   // plain-text fallback
```

QR payload (`qrPayload`): `SKYBOOK|<PNR>|FLIGHT <flightId or "?">|<contactName>` — compact and scannable; explicitly commented as a placeholder for a future check-in URL once a public front end exists.

---

# 6. Email Rendering — `BookingEmailTemplate`

Single `@Component`, no template engine (Thymeleaf/Freemarker) — plain Java text blocks with inline CSS, chosen because email clients strip external stylesheets anyway. `render(event, includeQr)` returns one HTML string.

## 6.1 Status badge

`switch` on `BookingEventType` → color pair: green (CONFIRMED/COMPLETED), red (CANCELLED/EXPIRED), amber (everything else — CREATED, "awaiting payment").

## 6.2 Route card (`routeCard`)

Renders **only** when both `originAirportCode` and `destinationAirportCode` are non-null; returns `""` otherwise (graceful degradation, not an error). Shows, per airport:

- The 3-letter IATA code (large, bold).
- **City name**, resolved via `AirportCityLookup.cityFor(code)` — added alongside this doc. Static `Map<String,String>` of ~70 common IATA codes → city; unknown codes simply render an empty city line (`nvl(cityFor(code), "")`), never "null" or a broken layout. This exists because flight-service treats airport codes as free-text 3-char strings with no backing airport/city reference table anywhere in the system (confirmed: no `Airport` entity, no seed data) — the alternative would have been a cross-service lookup call, rejected as unnecessary weight for a display-only nicety.
- Departs/Arrives time (pre-formatted string from the event; `"—"` if absent).

## 6.3 Passenger table

One row per `BookingEventPassenger`: name, seat (`"—"` if unassigned), class · fare type (via `pretty()` — `PREMIUM_ECONOMY` → `Premium economy`), check-in status (defaults to `NOT_OPEN`), fare (`money()` — `"<currency> <amount>"` or `"—"`).

## 6.4 QR block

Only appended when `includeQr` is true (§5.2). References the inline attachment by `Content-ID` (`BookingEmailTemplate.QR_CID = "skybook-qr"`), rendered as `<img src="cid:skybook-qr">` — must be added to the `MimeMessageHelper` **after** `setText()`, or the inline part is silently dropped (documented pitfall in `EmailService`, §7).

## 6.5 Escaping

All user/producer-controlled string fields go through `escape()` (`&`, `<`, `>` only — no attribute-context escaping, but the template never places these values inside an HTML attribute, only in element text content, so this is sufficient for the current markup).

---

# 7. Email Delivery — `EmailService`

| Method | Used by | Behavior |
|---|---|---|
| `sendEmail(EmailEvent)` | `EmailEventConsumer` | Delegates to the 3-arg overload. |
| `sendEmail(to, subject, body)` | Both consumers (plain-text paths) | `SimpleMailMessage`, synchronous `mailSender.send()`. |
| `sendHtmlEmail(to, subject, html)` | `BookingEventConsumer` (no-QR path) | `MimeMessageHelper`, `setText(html, true)`. |
| `sendHtmlEmail(to, subject, html, cid, png)` | `BookingEventConsumer` (QR path) | Adds an inline image part after the HTML body. |

Transport: `JavaMailSender` → Gmail SMTP (`smtp.gmail.com:587`, STARTTLS, auth). Credentials come from `MAIL_USERNAME`/`MAIL_PASSWORD` environment variables (Gmail app password, not the account password — standard Gmail SMTP requirement since it blocks plain password auth for third-party apps).

Send failures: `sendEmail` lets `JavaMailSender` exceptions propagate uncaught into the Kafka listener (→ container's default error handling — see §12); `sendHtmlEmail` wraps `MessagingException` (message-building failures only, not send failures) in `MailPreparationException`.

---

# 8. Testing

**Current state: essentially untested.** The only test in the module is:

```java
@SpringBootTest
class NotificationServiceApplicationTests {
    @Test void contextLoads() {}
}
```

No unit tests for `BookingEmailTemplate` (route-card null-handling, QR-inclusion-by-type logic, escaping, `pretty()`/`money()`/`nvl()` edge cases), no tests for either consumer (contact-email-blank drop path, passengers-null fallback path), no tests for `EmailService` (mocked `JavaMailSender`), no tests for `AirportCityLookup`, and no Kafka integration test (unlike booking/payment/inventory's Testcontainers-backed full-stack suites). This is the single biggest coverage gap in the fleet relative to its sibling services — every other module in `docs/` reports 50-150+ tests across unit/service/JPA/WebMvc/integration layers; this one has one smoke test.

## 8.1 Recommended testing plan (not yet built)

| Layer | Target | Cases |
|---|---|---|
| Unit — `BookingEmailTemplate` | Pure rendering | Route card renders iff both airport codes present; city line blank for unknown codes, populated for known ones; badge color per `BookingEventType`; QR block present/absent per `includeQr`; `pretty()`/`money()`/`nvl()`/`escape()` edge cases (null, blank, HTML-special chars in passenger names). |
| Unit — `AirportCityLookup` | Pure lookup | Known code (any case) resolves; unknown code returns `null`; `null` input returns `null`. |
| Unit — consumers | Mocked `EmailService`/`BookingEmailTemplate`/`QrCodeGenerator` | Blank/null `contactEmail` → no send; `passengers` null/empty → plain-text fallback path; `includeQr` true only for CREATED/CONFIRMED/COMPLETED. |
| Unit — `EmailService` | Mocked `JavaMailSender` | Correct `SimpleMailMessage`/`MimeMessageHelper` population; inline image only attached when PNG provided; `MessagingException` wrapped correctly. |
| Integration | Embedded Kafka (`spring-kafka-test`, already a test dependency) + `GreenMail` or similar fake SMTP | Publish a `BookingEvent` / `EmailEvent` on a real embedded broker, assert an email was "sent" with expected subject/recipient — closes the gap with the other services' full-stack IT suites. |

---

# 9. Configuration

```yaml
server.port: 8085

spring:
  application.name: notification-service
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: notification-service
      auto-offset-reset: earliest
      key-deserializer: StringDeserializer
      value-deserializer: JsonDeserializer
      properties:
        spring.json.trusted.packages: com.skybook.praveen.common.*
        spring.json.value.default.type: com.skybook.praveen.common.event.EmailEvent   # BookingEvent listener overrides via its own ConsumerFactory (§2)
  mail:
    host: smtp.gmail.com
    port: 587
    username: ${MAIL_USERNAME}
    password: ${MAIL_PASSWORD}
    properties.mail.smtp.auth: true
    properties.mail.smtp.starttls.enable: true
```

`MAIL_USERNAME`/`MAIL_PASSWORD` must be set in the environment (a Gmail address + app password) before the service will start correctly — no local defaults, no `.env` file checked into the repo.

No `spring-boot-starter-actuator` dependency — `/actuator/health` returns 404 even when the service is fully up. This is the false "DOWN" signal this doc follows on from a port-scan health check; if operational monitoring of this service becomes a real need, adding the actuator starter (already used by booking/inventory/payment) is a one-line `pom.xml` change.

---

# 10. Functional Requirements

## 10.1 Implemented

| ID | Requirement |
|---|---|
| FR-1 | The service shall consume `EmailEvent` messages from `skybook.email.events` and send a plain-text email to the specified recipient with the given subject/body. |
| FR-2 | The service shall consume `BookingEvent` messages from `skybook.booking.events` and, when `contactEmail` is present, send an email to that address. |
| FR-3 | If a `BookingEvent` carries passenger details, the service shall render an HTML email showing: booking reference (PNR), booking status badge, flight route (origin/destination airport code **and city name** where known, departure/arrival time) when flight context is present, a passenger table (name, seat, class, fare type, check-in status, fare), total fare, and payment status. |
| FR-4 | If a `BookingEvent` carries no passenger details, the service shall fall back to the plain-text subject/message the producer composed. |
| FR-5 | For `CREATED`, `CONFIRMED`, and `COMPLETED` booking events, the email shall include a scannable QR code encoding the PNR, flight id, and contact name. `CANCELLED`/`EXPIRED` events shall not include a QR code. |
| FR-6 | If `contactEmail` is missing or blank on a `BookingEvent`, the service shall skip sending and log a warning rather than fail. |
| FR-7 | Airport codes without a known city mapping shall render without a city name rather than showing an error or placeholder text. |

## 10.2 Planned but not implemented (per `ARCHITECTURE.md`, not built in this module)

| ID | Requirement | Status |
|---|---|---|
| FR-8 | Payment status update emails (`payment.completed`/`payment.failed`) | No producer publishes an event this service consumes for payment updates; payment-service's `PaymentEvent` (see `PAYMENT_SERVICE_MODULE.md` §10) has no consumer here yet. |
| FR-9 | Check-in / boarding-pass emails | No checkin-service exists yet. |
| FR-10 | Forgot-password emails | `EmailType.FORGOT_PASSWORD` enum value exists; no producer sends it. |
| FR-11 | Persisted notification log / delivery status | No database (§3); a send's only trace is a console log line and the Kafka consumer offset. |

---

# 11. Non-Functional Notes

- **Delivery is synchronous and blocking within the consumer thread** — `mailSender.send()` runs inline inside `@KafkaListener`, so a slow/unreachable SMTP server stalls consumption of that partition until it times out. No async dispatch, no outbound retry/backoff beyond whatever Spring Kafka's container-level error handling provides by default.
- **No rate limiting or batching** — one booking event produces one synchronous SMTP round-trip; a burst of bookings sends a burst of individual emails.
- **No PII redaction in logs** — `log.info` on booking event consumption includes `bookingReference`; email addresses/passenger names are not logged directly by this service (they pass through to `EmailService`, which only logs "sent to \<address\>" via `System.out.println`, not SLF4J — inconsistent with the rest of the module's logging and worth normalizing).

---

# 12. Known Risks / Open Questions

1. **No idempotency / dedup.** `auto-offset-reset: earliest` plus no send-log means a consumer restart, rebalance, or manual offset reset can **resend** an email for every booking event still in the topic's retention window. Acceptable today (dev/low volume); a `NotificationLog` table keyed on `(eventType, bookingReference/eventId)` would close this (ties into §3 and §10.2 FR-11).
2. **No dead-letter handling.** An SMTP exception thrown from inside `consume()` relies entirely on Spring Kafka's default container error handling (log + retry per the container factory's defaults, no custom `ErrorHandler`/DLT topic configured in `KafkaConfig`). A permanently-invalid address (bad `contactEmail`) could block partition progress or silently drop, depending on the default retry policy — not verified either way because there's no test for it (§8).
3. **`AirportCityLookup` coverage is a snapshot, not authoritative.** ~70 major airports; any origin/destination outside that set (which is easy — codes are free text, not validated against a real IATA list anywhere in the system, per §6.2) renders with a blank city line. This is intentional graceful degradation, not a bug, but the list will need occasional manual extension as more routes are used in demos/tests.
4. **Gmail SMTP as the only transport.** No abstraction (unlike payment-service's `PaymentGatewayClient` pattern) — swapping to SES/SendGrid/a transactional-email API means changing `EmailService` directly. Low risk today given the module's small size, but worth flagging if it grows.
5. **Two independent event flows in one service** means a schema/behavior change to the booking-email path (e.g. this session's route/city fix) requires zero coordination with the auth-email path, but also means there's no single "notification domain model" — the module is really two small features sharing a `pom.xml`.

---

# 13. Deferred / Out of Scope

- **`skybook_notification` database** — send/delivery log, per §3 and §10.2 FR-11. The largest concrete gap versus the original architecture doc.
- **Payment and check-in email flows** — §10.2 FR-8/FR-9, blocked on those services publishing consumable events.
- **Dead-letter topic / retry policy tuning** — §12.2.
- **Template engine migration** (Thymeleaf) — only worth it if templates grow enough that inline Java text blocks become unwieldy; not the case yet (one template, ~180 lines).
- **`spring-boot-starter-actuator`** — for health/metrics parity with booking/inventory/payment (§9).
- **Async/queued send path** — decouple SMTP latency from Kafka consumption (§11).
- **Automated test suite** — §8.1's plan, currently unbuilt.

---

# 14. Operational Notes

- **Health check gotcha:** `curl :8085/actuator/health` (or any actuator probe) returns 404 even when the service is healthy — there is no actuator dependency (§9). Don't use it as a liveness signal; check the port is listening and/or grep the consumer's `log.info` lines instead.
- **Startup requires `MAIL_USERNAME`/`MAIL_PASSWORD`** in the environment — the service will fail to start (or fail on first send, depending on Spring Mail's lazy-init behavior) without a valid Gmail address + app password.
- **Code changes require a full restart**, not just a recompile — this service (like every module here) is typically run via an IDE run configuration directly off `target/classes`; a running JVM keeps its already-loaded class bytecode regardless of what's recompiled on disk. This was the root cause of a "the fix isn't showing up" investigation earlier in this branch's work (a sibling service, booking-service, was the stale one that time, but the same gotcha applies here).

---

*Companion to `BOOKING_SERVICE_MODULE.md`, `PAYMENT_SERVICE_MODULE.md`, `INVENTORY_SERVICE_MODULE.md`, `FLIGHT_SCHEDULING_MODULE.md`. Written after the Sprint 6 booking-email route/city-name fix on `feature/booking-payment-integration`.*
