# SKB-DOC-04 — Interface Control Document (ICD)

| | |
|---|---|
| **Document ID** | SKB-DOC-04 |
| **Version** | 1.0 |
| **Status** | Baselined |
| **Owner** | Platform Engineering |
| **Effective date** | 2026-08-01 |
| **Scope** | Every interface crossing a service boundary: HTTP routes, event topics and payloads, and their compatibility rules |

## 1. Interface classes and their contracts

| Class | Transport | Contract authority | Compatibility rule |
|---|---|---|---|
| Edge API | HTTP via api-gateway (`/api/**`) | Controller + this register | Additive-only within a major version; removals need a deprecation release |
| Internal sync | Feign, service→service | Client interface in the calling service | Caller and callee deploy together (compose ships as a set) |
| Events | Kafka | Shared classes in `skybook-common` | §5.4 — the strictest rules in the system |

## 2. Gateway route table (edge)

All external traffic enters `api-gateway:8080`. The frontend's nginx proxies
same-origin `/api/**` to it (stripping `Origin`, making deployment
domain-agnostic).

| Route prefix | Target service |
|---|---|
| `/api/auth/**`, `/api/profile/**` | auth-service |
| `/api/flights/**`, `/api/flight-schedules/**` | flight-service |
| `/api/bookings/**` (incl. `/quote`, `/fare-calendar`, `/fare-alerts`) | booking-service |
| `/api/seats/**`, `/api/reservations/**` (inventory paths) | inventory-service |
| `/api/payments/**`, `/api/refunds/**`, `/api/invoices/**` | payment-service |
| `/api/checkins/**`, `/api/boarding-passes/**`, `/api/baggage/**`, `/api/manifests/**` | checkin-service |

### 2.1 Public paths (no token)

Registered in **two places that must stay in step** (gateway
`JwtAuthenticationFilter` and the owning service's `SecurityConfig`); the
four-gate rule for making an endpoint public is in `FRONTEND_MODULE.md`:

`/api/auth/register`, `/api/auth/login`, `/api/auth/logout`,
`/api/auth/forgot-password`, `/api/auth/reset-password`,
`/api/auth/sso/providers`, `/api/auth/oauth2/authorization/google`,
`/api/auth/oauth2/callback/google`, `/api/bookings/guest-session`,
plus public shopping data (flight search, quote, fare calendar).

`/api/auth/login` responds with the **raw JWT as text/plain**, not JSON — a
known contract that clients must not "fix" unilaterally.

## 3. Authentication & authorisation summary

Bearer JWT (RS256) on every non-public call. Roles: `ROLE_USER`,
`ROLE_ADMIN`, `ROLE_SERVICE` — a token carries exactly one. Object-level
ownership is enforced in the owning service from the token subject (guards
like `requireOwnerOfBooking`); ADMIN and SERVICE bypass ownership, never
silently but by explicit `isPrivileged()` checks. Full model: SKB-DOC-06.

## 4. Edge endpoint register (selected, by service)

The complete machine-readable contract is each service's springdoc OpenAPI
(`/v3/api-docs`); this register lists the endpoints whose semantics carry
business rules. New endpoints must be added here in the same PR.

### booking-service

| Endpoint | Semantics (binding) |
|---|---|
| `POST /api/bookings` | Creates the whole journey (connection legs ≤2, optional return) as ONE booking; validates bookability (60-min cutoff) and chronology; idempotency key honoured |
| `POST /api/bookings/quote` · `GET /api/bookings/fare-calendar` | Public shopping data; same `FareCalculator` as checkout |
| `GET /api/bookings/mine` | Owner-scoped by token subject; no id to tamper with |
| `GET /api/bookings/{id}/cancellation-preview` | Live cancellation quote: tier in force, deadlines, per-row refunds; never throws for policy reasons — blocked states are *reported* |
| `PATCH /api/bookings/{id}/cancel` | Whole-booking cancel under CancellationPolicy; ADMIN = desk (no window) |
| `POST /api/bookings/{id}/passengers/cancel` | Partial cancel; expands to every segment-row of the selected travellers; guardian rule (minor never left alone); checked-in rows refused individually |
| `POST /api/bookings/{id}/segments/{i}/cancel` | Return-only rule: only direction-1 segments cancel alone |
| `POST /api/bookings/{id}/segments/{i}/rebook` | Premium date change: exchange, not refund |
| `POST /api/bookings/{id}/passengers/{rowId}/seat` | Pre-check-in seat change under the entitlement ceiling |
| `/api/bookings/fare-alerts` (POST/GET/DELETE) | Fare watch CRUD, owner-scoped |

### checkin-service

| Endpoint | Semantics |
|---|---|
| `PATCH /api/checkins/{id}/checkin` | Owner-guarded; window T-24h → T-45m; issues the pass |
| `PATCH /api/checkins/{id}/seat` | Post-check-in seat change, ALL fares capped at paid surcharge; reissues the pass and revokes the prior one |
| `GET /api/boarding-passes/checkin/{id}` | Active pass only; revoked passes 404 |
| Boarding-pass verify (admin) | Validates the signed QR token |

### payment-service

| Endpoint | Semantics |
|---|---|
| `PATCH /api/payments/{id}/authorize` / `/capture` | Passenger flow; capture confirms the booking downstream |
| `PATCH /api/payments/{id}/cancel` / `/refund`, `GET /api/refunds` | ADMIN only |
| `GET /api/payments/booking/{bookingId}` | Owner-guarded read |

## 5. Event catalogue (Kafka)

### 5.1 Topics

| Topic | Producer | Consumers |
|---|---|---|
| `skybook.booking.events` | booking-service | payment, checkin, notification |
| `skybook.payment.events` | payment-service | booking, notification |
| `skybook.checkin.events` | checkin-service | booking (mirror), notification |
| `skybook.flight.events` | flight-service | notification (+ any) |
| `skybook.inventory.events` | inventory-service | (diagnostic) |
| `skybook.email.events` | any | notification |

Every consumer group has an error handler with a dead-letter topic
(`RESILIENCE_MODULE.md` §DLT); a poison message never blocks a partition.

### 5.2 BookingEvent (the load-bearing contract)

Types: `CREATED`, `CONFIRMED`, `CANCELLED`, `PARTIALLY_CANCELLED`,
`EXPIRED`, `COMPLETED`, `FARE_ALERT`.

Structure: envelope (type, bookingId, PNR, contact, subject/message,
ownerSubject) + **nested `segments[]`** each carrying its flight facts
(times, terminals) and its passengers (with per-row ids, seats, fares,
ticket numbers). Money facts on cancellation events: `refundTierPercent`,
`refundBreakdown` (compact `"FLEXI:100.00;SAVER:80.00"` — parsed only by
payment's `RefundCalculator`), `cancelledBookingPassengerIds`.

Binding consumer semantics:

| Event | payment-service | checkin-service | notification |
|---|---|---|---|
| CREATED | create PENDING payment (idempotent by bookingId) | — | booking-created mail |
| CONFIRMED | — | create per-segment check-in records | confirmation mail + e-ticket |
| CANCELLED | refund per breakdown×tier; tier 0 ⇒ NO refund; uncaptured ⇒ void | cancel all check-ins, revoke passes | cancellation mail with exact refund statement |
| PARTIALLY_CANCELLED | refund exactly the breakdown lines ⇒ PARTIALLY_REFUNDED | cancel exactly the listed rows' check-ins | booking-updated mail with exact refund |
| FARE_ALERT | ignore | ignore | plain-text fare mail |

### 5.3 CheckInEvent

Types: `PASSENGER_CHECKED_IN`, `BOARDING_PASS_GENERATED`,
`PASSENGER_BOARDED`, `PASSENGER_NO_SHOW`, `PASSENGER_CHECKIN_CANCELLED`.
Carries seat, terminals, pass number, signed QR token, boarding facts.
Booking-service mirrors status **and seat** from these events — the mirror is
what makes cancellation release the seat the passenger actually holds
(FR-CANX-05). Notification renders the pass PDF from the event alone (no
synchronous call back), so the event must stay self-sufficient: field
removals are forbidden without a notification-service release first.

### 5.4 Event compatibility rules (strict)

1. **Additive only.** New fields default to null and every consumer must
   tolerate their absence (old events replay forever).
2. **New enum values deploy consumers-first.** A consumer that has never
   seen the constant will fail deserialisation into its DLT. All consumers
   of the topic ship before or with the first producer of the value
   (precedent: `FARE_ALERT`, `PARTIALLY_CANCELLED`).
3. **Removals require a mirror release**: old and new shape produced in
   parallel one release, consumers migrated, then the old shape dropped
   (precedent: flat BookingEvent fields → nested segments).
4. **Consumers are idempotent and order-tolerant** (NFR-07): duplicate
   delivery is a no-op; a late event must not regress newer state (e.g. the
   check-in mirror keeps its more advanced status).
5. Events carry **facts with enough context to act on alone** — a consumer
   should not need a synchronous read-back to process one.

## 6. Change procedure

Interface changes follow SKB-DOC-07 §5 with this addition: the PR that
changes any interface must update this document (route table, register, or
event catalogue) **in the same commit**, and event-contract changes must
name their rollout ordering per §5.4 in the PR description.
