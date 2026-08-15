# SKB-DOC-01 — Software Requirements Specification (v2)

| | |
|---|---|
| **Document ID** | SKB-DOC-01 |
| **Version** | 2.0 (supersedes SRS v1) |
| **Status** | Baselined |
| **Owner** | Product & Platform Engineering |
| **Effective date** | 2026-08-01 |
| **Verification** | Each requirement traces to design, code, and test evidence in SKB-DOC-11 |

## 1. System overview

SkyBook is an airline reservation platform: flight shopping, booking with
real inventory control, payment with real refund accounting, online check-in
with boarding-pass issuance, and a complete back-office. It is built as nine
Spring Boot microservices behind an API gateway, a React single-page
application, PostgreSQL (database-per-service), and Kafka for inter-service
events. Every behaviour is real within the platform's own boundary; the only
simulated externality is the card processor (a deterministic gateway
simulator inside payment-service).

### 1.1 Requirement conventions

- `FR-<AREA>-<nn>` — functional requirement. `NFR-<nn>` — non-functional.
- Every FR/NFR is **binding**. "Should" does not appear in this document;
  aspirations live in the backlog, not the SRS.
- Areas: SRCH (shopping), BOOK (booking), FARE (pricing & fare families),
  PAY (payment & refunds), CANX (cancellation), CHK (check-in & boarding),
  TKT (ticketing), ACCT (accounts & profile), ADM (back-office),
  NOTF (notifications), INTL (internationalisation).

## 2. Actors

| Actor | Description |
|---|---|
| Guest | Unauthenticated visitor. May shop and begin a guest check-in. |
| Passenger | Registered user (`ROLE_USER`). Owns bookings. |
| Back-office agent | `ROLE_ADMIN`. Operates the admin console; bypasses passenger-side time windows where explicitly stated. |
| Service | `ROLE_SERVICE`. Machine identity for service-to-service calls. |

## 3. Functional requirements

### 3.1 Shopping — SRCH

- **FR-SRCH-01** A guest (no authentication) can search flights by origin,
  destination, and date. Authentication is required only at booking time.
- **FR-SRCH-02** Search supports one-way, round-trip, and multi-city (up to
  three legs, dates strictly ascending, each leg's origin chained from the
  previous leg's destination).
- **FR-SRCH-03** Results include direct flights and connections. Same-carrier
  connections with a layover of at least 45 minutes are offered as a single
  protected **through-ticket**; mixed-carrier combinations require at least
  60 minutes and are labelled self-transfer. Multi-city results exclude
  self-transfer combinations.
- **FR-SRCH-04** Every result card shows a from-price per passenger derived
  from the same fare calculator checkout will use (no bait pricing).
- **FR-SRCH-05** A per-date fare calendar shows the lowest fare per day for a
  route and cabin, priced by the same calculator.
- **FR-SRCH-06** Flights departing within 60 minutes (or departed) are not
  offered for sale, and the booking service independently rejects them
  regardless of what a client submits.

### 3.2 Booking — BOOK

- **FR-BOOK-01** A booking is identified by a six-character PNR and may span
  multiple flight segments under that single PNR: outbound connection legs,
  and a return, in any supported combination.
- **FR-BOOK-02** One payment covers the entire journey regardless of segment
  count.
- **FR-BOOK-03** The booking flow captures per-passenger identity (with
  passport validity checked against the travel date), per-passenger cabin and
  fare family, per-leg seat selection, per-direction extra bags (£40 each),
  and a contact (name, e-mail, optional phone).
- **FR-BOOK-04** Seat state is owned by inventory-service: a draft booking
  holds seats; payment capture converts holds to reservations; any failure
  path releases every hold taken (no orphaned seat state).
- **FR-BOOK-05** Booking creation and payment operations are idempotent: a
  retried request with the same idempotency key returns the original result
  and must not double-charge or double-book (`IDEMPOTENCY_MODULE.md`).
- **FR-BOOK-06** A signed-in passenger can save traveller profiles and
  quick-fill them into the passenger form.
- **FR-BOOK-07** A booking modification ("change flight, dates or bags") is
  executed as a rebooking: new booking at today's fares, then the original is
  cancelled under the cancellation policy. The UI quotes the **net**
  cost/refund before confirmation and refuses a no-op rebooking (same flight,
  same bags).
- **FR-BOOK-08** Premium-fare passengers can move one segment to another
  flight on the same booking and tickets (exchange, not refund), paying only
  the fare difference.

### 3.3 Pricing & fare families — FARE

- **FR-FARE-01** Cabins: Economy, Premium Economy, Business, First. Fare
  families per cabin: Saver, Flexi, Premium, each with published rules
  (change/cancel entitlements) shown before purchase.
- **FR-FARE-02** Fares are demand-shaped: the price for a departure date
  reflects load and lead time, computed by one `FareCalculator` shared by
  results, calendar, quote, and checkout. The stored fare on a booked
  passenger row never changes retroactively.
- **FR-FARE-03** Seat surcharges are tiered by seat desirability; Flexi and
  Premium select seats free; Saver pays the listed surcharge. The surcharge
  actually paid is recorded per passenger and becomes their later
  entitlement ceiling (FR-CHK-06, FR-BOOK-07).
- **FR-FARE-04** A passenger can watch a route/date/cabin; the system
  reprices hourly with the checkout calculator and e-mails on movement
  (fare watch).

### 3.4 Payment & refunds — PAY

- **FR-PAY-01** Payment lifecycle: PENDING → AUTHORIZED → CAPTURED, with
  explicit failure states; capture confirms the booking and issues tickets.
- **FR-PAY-02** Every refund is an explicit record: amount, withheld
  cancellation fee, reason, gateway reference. Cumulative refunds can never
  exceed the captured amount.
- **FR-PAY-03** Refund amounts are computed from the stored per-fare-family
  breakdown (Saver withholds a 30% fee) composed with the time tier
  (FR-CANX-02); the passenger is quoted the exact amount before confirming,
  and the executed refund equals the quote.
- **FR-PAY-04** Partial cancellations (passengers or a segment off a
  surviving booking) move real money: payment becomes PARTIALLY_REFUNDED for
  exactly the cancelled rows' value; the booking remains live for the rest.
- **FR-PAY-05** An invoice is generated at capture and is immutable; refunds
  produce credit notes rather than mutating it.

### 3.5 Cancellation — CANX

- **FR-CANX-01** A passenger can cancel online: the whole booking, selected
  passengers, or the return segment alone. Cancelling the outbound while
  keeping the return is refused (no-show trap).
- **FR-CANX-02** Refunds are time-tiered against the earliest still-upcoming
  departure: ≥72h → 100% of the fare-rule refund; 24–72h → 50%; <24h → 0%
  (cancellation still allowed; it frees the seats); <2h or departed → online
  cancellation closed. Premium fares ride their own flatter waiver tier
  (100% until 6h, then 50%) per their published rules. Mixed bookings apply
  each line's own tier.
- **FR-CANX-03** Before confirming, the passenger sees a live charges chart:
  the tier bands with deadlines, a countdown to the next drop, and the exact
  refund for what they selected. The quote is server-computed and the
  executed refund matches it.
- **FR-CANX-04** Checked-in passengers can cancel the **whole booking**
  online: issued boarding passes are revoked and all seats released.
  Cancelling a checked-in passenger individually is refused (no mechanism
  exists to void a single pass) with guidance to whole-cancel or the desk.
- **FR-CANX-05** Every cancellation path releases the cancelled seats back to
  inventory — including seats changed after check-in (the booking tracks the
  seat actually held, not the one originally booked).
- **FR-CANX-06** A back-office agent cancelling is the desk: no time window,
  fare rules alone.
- **FR-CANX-07** Unpaid bookings cancel freely at any time; nothing was
  charged so nothing is refunded.

### 3.6 Check-in & boarding — CHK

- **FR-CHK-01** Check-in opens 24 hours and closes 45 minutes before
  departure, per passenger, per direction of a multi-segment booking.
- **FR-CHK-02** Check-in is available to registered owners and, for a guest
  booking, through a guest check-in session without an account.
- **FR-CHK-03** Check-in issues a boarding pass: pass number, PNR, flight,
  seat, cabin, gate/boarding group, departure **and arrival terminals**
  (real per-carrier terminal assignments), and a signed QR token verifiable
  by the back office.
- **FR-CHK-04** The boarding pass e-mailed as a PDF attachment presents the
  same fields, value for value, as the on-screen pass.
- **FR-CHK-05** Passengers who never check in are swept to NO_SHOW when the
  gate closes; their coupons remain unused rather than flown.
- **FR-CHK-06** Seat changes: before check-in from the trip page (Flexi and
  Premium free within cabin; Saver up to the surcharge already paid); after
  check-in from the trip page with every fare capped at the surcharge
  already paid — the boarding pass is reissued with the new seat and the
  prior pass invalidated.

### 3.7 Ticketing — TKT

- **FR-TKT-01** Payment capture issues one e-ticket per traveller
  (deterministic 125-prefixed number) with one coupon per flight segment.
- **FR-TKT-02** Coupon lifecycle: OPEN → CHECKED_IN → FLOWN (hourly sweep
  after departure); cancellation moves coupons to REFUNDED when money
  returned, CANCELLED when forfeited or exchanged. FLOWN coupons are history
  and never change again.
- **FR-TKT-03** A styled e-ticket (itinerary, passengers, fare calculation,
  both directions) is downloadable from the trip page.

### 3.8 Accounts & profile — ACCT

- **FR-ACCT-01** Registration, login, logout, forgot/reset password (e-mail
  link pointing at the deployed origin), and "Sign in with Google" SSO.
- **FR-ACCT-02** A profile hub shows the next upcoming trip with contextual
  nudges (pay, check in), saved travellers, fare watches, and account
  preferences (language, currency) that apply at sign-in on any device.
- **FR-ACCT-03** My Trips groups bookings by lifecycle — pending check-in,
  checked in, completed, no-show, cancelled — derived from passenger
  check-in state and the last leg's departure, with sticky filter chips.
- **FR-ACCT-04** Object-level ownership: a passenger can only read or act on
  bookings, check-ins, and payments they own; ownership is enforced in each
  service from the token subject, never from client-supplied identifiers.

### 3.9 Back-office — ADM

- **FR-ADM-01** An admin console exposes every back-office operation:
  flight and schedule management (including creating flights with cabin
  configuration on the modern fleet), booking search across all customers,
  cancellations, payment/refund inspection, boarding-pass verification (QR
  token validation), seat-map inspection, and manifests.
- **FR-ADM-02** Console access requires `ROLE_ADMIN`; there is no
  self-service path to that role (bootstrap is an operator-controlled
  environment variable).

### 3.10 Notifications — NOTF

- **FR-NOTF-01** E-mail on: booking created, confirmed (with e-ticket
  details), cancelled (with the exact refund statement, including the
  zero-refund wording), partially cancelled (with the exact partial refund),
  check-in confirmed with the boarding-pass PDF attached, fare-watch alerts,
  and password reset.
- **FR-NOTF-02** Notification delivery is event-driven (Kafka) only;
  notification-service makes no synchronous calls into other services, and a
  notification failure never fails the business operation that triggered it.

### 3.11 Internationalisation — INTL

- **FR-INTL-01** The UI ships in 10 languages (including Telugu); Arabic
  renders fully right-to-left. Chrome and the booking flow are translated;
  long-form prose may remain English (documented limitation).
- **FR-INTL-02** Display currency is selectable (GBP, USD, EUR, INR, AED,
  JPY); conversion is display-only at published demo rates and charging is
  in GBP. Amounts the user will actually pay are always shown exactly.

## 4. Non-functional requirements

- **NFR-01 Security.** RS256-signed JWTs issued by auth-service and verified
  everywhere; role separation USER/ADMIN/SERVICE with distinct audiences;
  per-service client credentials for machine tokens; all secrets injected via
  environment with fail-fast validation (no compiled-in defaults). Full
  model: SKB-DOC-06.
- **NFR-02 Resilience.** Every synchronous inter-service call has explicit
  timeouts, circuit breakers, and bulkheads; Kafka consumers have
  error-handling with dead-letter topics; producer send failures are logged
  at ERROR into the centralised pipeline. Degradation is graceful: shopping
  survives inventory being down; cleanup paths are quiet and idempotent.
- **NFR-03 Observability.** Metrics (Prometheus), logs (structured JSON →
  Loki), traces (OpenTelemetry → Tempo) with one Grafana front end;
  liveness/readiness health groups on every service.
- **NFR-04 Data integrity.** Database-per-service; schema changes only
  through versioned Flyway migrations; optimistic locking on money-bearing
  aggregates; state machines enforce legal transitions and reject the rest.
- **NFR-05 Auditability.** Money and state transitions append history
  records (who, when, source, correlation); refunds and invoices are
  explicit immutable rows.
- **NFR-06 Quality gate.** CI enforces build + full test suite + SonarCloud
  quality gate (coverage ≥ 80% on new code; zero new bugs/vulnerabilities)
  before merge to main. Current baseline: 91.2% coverage, 0 open
  bugs/vulnerabilities.
- **NFR-07 Idempotent messaging.** Every Kafka consumer tolerates duplicate
  delivery and out-of-order arrival without corrupting state (documented
  per-consumer in the module designs).
- **NFR-08 Deployability.** The entire platform starts from one
  `docker compose up` with only `.env` as input; production differs from
  development by an overlay file, not by divergent artefacts.
- **NFR-09 Performance.** Flight search over the full ~440k-flight schedule
  returns paginated results without unbounded memory (the 920k-row OOM class
  of defect is regression-guarded); UI interactions (filtering, language and
  currency switching) do not reload the page.
- **NFR-10 Recoverability.** All services restart cleanly after host reboot;
  disaster-recovery procedure per `docs/DR_RUNBOOK.md`.

## 5. Known accepted limitations

Documented deviations, accepted by the product owner, tracked in
`TEST_REPORT_PASSENGER_FEATURES.md` §4: modify-dialog reprices leg 1 of a
multi-segment booking (per-segment tools cover the rest); multi-city bags
charge once per direction-0 chain; a round-trip return is a single flight;
display-only FX; long-form prose untranslated; time windows are evaluated
against airport-local schedule times compared with server time (timezone
normalisation is a registered backlog item).
