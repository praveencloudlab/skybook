# Microservices Documentation

This page is the service-by-service reference for the SkyBook platform: what each microservice owns, which port and database it uses, which Kafka topics it produces and consumes, and the design decisions that matter when working on or integrating with it. It is written for engineers joining the codebase and for anyone tracing a request or event across service boundaries. Deployment topology (Docker Compose, environments, the promote ladder) is covered by sibling pages; this page stays at the level of the individual services.

## Platform at a glance

The backend is a single Maven reactor (`backend/pom.xml`, `com.skybook.praveen:skybook-backend:1.0.0-SNAPSHOT`) on **Java 21, Spring Boot 3.5.16, Spring Cloud 2025.0.0**. It contains eight deployable Spring Boot applications (seven domain services plus the API gateway) and three non-deployable modules (`skybook-common`, `skybook-security`, `e2e-tests`). A React/Vite/TypeScript SPA fronts the platform. All services share one Postgres 16 container with a database per service (created by `docker/postgres/init-databases.sql`) and one single-broker Kafka 3.9.0 (KRaft) cluster.

| Service | HTTP port | Management port | Database | Kafka | Reachable from host |
|---|---|---|---|---|---|
| api-gateway | 8080 | 9080 | — | no | yes (`8080:8080`) |
| auth-service | 8081 | 9081 | `skybook_auth` | producer | no (internal only) |
| flight-service | 8082 | 9082 | `skybook_flight` | no | no |
| booking-service | 8083 | 9083 | `skybook_booking` | producer + consumer | no |
| inventory-service | 8084 | 9084 | `skybook_inventory` | producer | no |
| notification-service | 8085 | 9085 | — (stateless) | consumer | no |
| payment-service | 8086 | 9086 | `skybook_payment` | producer + consumer | no |
| checkin-service | 8087 | 9087 | `skybook_checkin` | producer + consumer | no |
| frontend (nginx) | 3000 | — | — | no | yes (`3000:3000`) |

Conventions shared by every backend service:

- **Actuator on a separate internal-only management port** (`9081`–`9087`, `9080` for the gateway) exposing `health,info,metrics,prometheus` (plus `circuitbreakers` on booking/inventory/checkin); `/livez` and `/readyz` are additionally mapped on the main port for Kubernetes probes.
- **JWT validation is verify-only outside auth-service**: every service holds only the RS256 public key (`JWT_PUBLIC_KEY`), issuer default `skybook-auth`, user audience default `skybook-api`, plus its own name as service audience.
- **Locked-down containers**: read-only root filesystem with a writable `/tmp` tmpfs; Postgres and Kafka are `expose:`-only on the compose network, never host-published.
- **Observability**: each JVM runs the OpenTelemetry Java agent 2.14.0 exporting traces via OTLP/gRPC to Tempo.
- **Resilience** (booking, inventory, checkin): OpenFeign with 2 s connect / 5 s read timeouts, Resilience4j circuit breakers (COUNT_BASED window 10, 50% failure threshold, 10 s open), retry on reads only (3 attempts, exponential backoff), bulkheads (10 concurrent calls). 4xx responses are deliberately not recorded as circuit-breaker failures.

## Kafka topics

Topic names are constants in `skybook-common` (`com.skybook.praveen.common.constants.KafkaTopics`); event payloads are the shared DTOs in `com.skybook.praveen.common.event`. Keys are Strings, values JSON.

| Topic | Produced by | Consumed by | Event types |
|---|---|---|---|
| `skybook.email.events` | auth-service | notification-service | `EmailEvent`: REGISTRATION_SUCCESS, FORGOT_PASSWORD, EMAIL_VERIFICATION |
| `skybook.booking.events` | booking-service | payment-service, checkin-service, notification-service | `BookingEvent`: CREATED, CONFIRMED, CANCELLED, PARTIALLY_CANCELLED, EXPIRED, COMPLETED, FARE_ALERT |
| `skybook.payment.events` | payment-service | booking-service | `PaymentEvent`: PAYMENT_SUCCEEDED, PAYMENT_FAILED, PAYMENT_CANCELLED, REFUND_COMPLETED, REFUND_FAILED |
| `skybook.checkin.events` | checkin-service | booking-service, notification-service | `CheckInEvent`: PASSENGER_CHECKED_IN, BOARDING_PASS_GENERATED, PASSENGER_BOARDED, PASSENGER_NO_SHOW, PASSENGER_CHECKIN_CANCELLED |
| `skybook.inventory.events` | inventory-service | — (no in-platform consumer today) | `InventoryEvent`: INVENTORY_CREATED, SEAT_HELD, SEAT_RELEASED, HOLD_EXPIRED, SEAT_RESERVED, RESERVATION_CANCELLED |
| `skybook.flight.events` | — | — | Declared in `KafkaTopics` but currently unused |

---

## api-gateway

| Fact | Value |
|---|---|
| Port | 8080 (the only host-published backend port) |
| Management port | 9080 |
| Database | none |
| Kafka | none |
| Module | `backend/api-gateway` |

The single public edge of the platform. Built on Spring Cloud Gateway Server MVC with a **static routing table** (`GatewayRoutesConfig` — one `RouterFunction` per downstream service, base URLs from `services.*.base-url`); there is no service discovery anywhere in the codebase. Routes are pure pass-through with no path rewriting.

Notable design points:

- **Auth routes are listed explicitly, never by wildcard**, so `/api/auth/service-token` (the machine-token endpoint) can never be reached from the public edge. Adding a new auth endpoint means adding it to the route table on purpose.
- **JWT verification at the edge** with the public key only; `accept-service-tokens: false` means a machine token can never enter through the gateway, while guest tokens pass through (each downstream service opts in individually).
- Filters: `JwtAuthenticationFilter`, `RateLimitFilter` (100 requests/minute), `RequestLoggingFilter`, and `DownstreamErrorHandlingFilter`, which converts an unreachable downstream into a clean 502 — which is also why the gateway declares no `depends_on` for the app services.
- CORS allows `http://localhost:5173` (Vite dev) and `http://localhost:3000` (nginx frontend).

## auth-service

| Fact | Value |
|---|---|
| Port | 8081 |
| Management port | 9081 |
| Database | `skybook_auth` (Flyway-managed, `ddl-auto: validate`) |
| Kafka produces | `skybook.email.events` |
| Kafka consumes | — |
| Key entities | `User`, `UserRole`, `EmailVerificationOtp`, `PasswordResetToken`, `FederatedIdentity`, `SavedTraveller`, `ServiceClient` |

Identity and token authority. Owns registration, login/logout/session (`AuthController`), passenger profile and saved travellers (`ProfileController`), and machine-token issuance (`ServiceTokenController`). It is the **only holder of the RS256 private signing key**; every other service and the gateway verify with the public key. User tokens live 60 minutes, `ROLE_SERVICE` tokens 10 minutes.

Notable design points:

- **Email verification via OTP** (`EmailVerificationOtp`) and password reset (`PasswordResetToken`); both flows send mail by publishing `EmailEvent`s to Kafka rather than sending SMTP directly.
- **"Sign in with Google" SSO** (`sso` package, `FederatedIdentity`): an empty `GOOGLE_CLIENT_ID` switches the feature off cleanly — OAuth beans are never created, `/api/auth/sso/providers` returns `[]`, and the frontend hides the button.
- **Service-client registry** (`ServiceClient`, secrets BCrypt-hashed, provisioned at startup): booking-service (audiences `flight-service,inventory-service`, plus the exclusive right to mint guest tokens), checkin-service (`flight-service,inventory-service`), inventory-service (`flight-service`). payment-service is intentionally **not** registered — it makes no outbound HTTP calls, so it holds no credential (least privilege).
- A `SKYBOOK_BOOTSTRAP_ADMIN_EMAIL` promotes one already-registered user to ADMIN at startup.

## flight-service

| Fact | Value |
|---|---|
| Port | 8082 |
| Management port | 9082 |
| Database | `skybook_flight` (Hibernate `ddl-auto: update`) |
| Kafka | none |
| Key entities | `Flight`, `FlightSchedule`, `TerminalPolicy` |

The flight catalog: flights, schedules and search (`FlightController`, `FlightScheduleController`, plus a `scheduler` package for time-based jobs). It is the read-side dependency for most of the fleet — booking, inventory and check-in all call it via Feign for flight validation, status and departure times, which is why it accepts `ROLE_SERVICE` tokens with audience `flight-service`. It publishes and consumes no Kafka events. Swagger UI is available on the service at `/swagger-ui.html` (as on all domain services with controllers).

## booking-service

| Fact | Value |
|---|---|
| Port | 8083 |
| Management port | 9083 |
| Database | `skybook_booking` (Flyway-managed, `ddl-auto: validate`) |
| Kafka produces | `skybook.booking.events` |
| Kafka consumes | `skybook.payment.events`, `skybook.checkin.events` |
| Sync dependencies | flight-service, inventory-service (Feign + Resilience4j) |
| Key entities | `Booking`, `BookingSegment`, `BookingPassenger`, `Passenger`, `BookingContact`, `BookingPayment`, `Ticket`, `TicketCoupon`, `BookingHistory`, `FareAlert`, `GuestLookupAttempt` |

The orchestrator of the purchase journey. Creates draft bookings (validating the flight against flight-service and holding seats against inventory-service), generates PNRs (`PnrGenerator`), prices fares and taxes (`FareCalculator`, `TaxPolicy`), and drives the booking state machine (`BookingStateMachine`). Round trips are a single PNR with multiple `BookingSegment`s.

Notable design points:

- **Saga via Kafka, not HTTP**: booking emits `BookingEvent`s; payment-service reacts, and the returning `PAYMENT_SUCCEEDED` on `skybook.payment.events` is what confirms the booking (`PaymentEventConsumer`). Check-in state is mirrored back per passenger from `skybook.checkin.events` (`CheckInEventConsumer`) to arm the cancellation guard.
- **Idempotency**: `POST` booking creation accepts an `Idempotency-Key` header (`BookingController` → `BookingFacade`), keyed on the `Booking` entity.
- **Ticketing with validating-airline stock**: tickets and per-segment coupons are issued in-service; the ticket number is `stock prefix + 8-digit booking id + 2-digit traveller index`, where the prefix comes from a real IATA stock map (`EK→176, BA→125, AI→098, 6E→312, SG→775, EI→053`; unknown carriers fall back to legacy stock `125`). The validating airline is derived from the first marketing carrier of the journey.
- **Draft TTL sweep**: DRAFT bookings older than 15 minutes are cancelled by `StaleDraftSweepJob` — the booking-side twin of inventory's seat-hold expiry (both 15 minutes by design).
- **Cancellation policy** (`CancellationPolicy`): STANDARD ladder of full refund ≥72 h, half ≥24 h, closed <2 h; SAVER withholds a 30% fee (deliberately equal to payment-service's `payment.refund.saver-fee-percent`); PREMIUM is fully refundable until 6 h before departure, 50% thereafter.
- **Guest surface**: `GuestSessionController` verifies reference + surname and (via its exclusive registry grant) asks auth-service to mint guest tokens; `accept-guest-tokens: true` here and in checkin-service only.

## inventory-service

| Fact | Value |
|---|---|
| Port | 8084 |
| Management port | 9084 |
| Database | `skybook_inventory` (Hibernate `ddl-auto: update`) |
| Kafka produces | `skybook.inventory.events` |
| Kafka consumes | — |
| Sync dependencies | flight-service (Feign) |
| Key entities | `Aircraft`, `AircraftSeat`, `FlightInventory`, `SeatHold`, `SeatReservation`, `InventoryHistory` |

Seat and cabin inventory: aircraft definitions and seat maps (`SeatMapGenerator`, `AircraftController`, `AircraftSeatController`), per-flight cabin availability (`FlightInventoryController`), and the hold/reserve/release lifecycle behind seat selection (`SeatReservationController`, `InventoryStateMachine`).

Notable design points:

- **Seat holds with TTL**: a hold blocks a seat for 15 minutes; `SeatHoldExpiryJob` sweeps every 60 s and releases expired holds (recorded in inventory history as `HOLD_EXPIRED`; no Kafka event is published for expiry — the enum constant exists but has no producer today).
- **Free auto-assign vs paid selection**: `AutoSeatSelector` assigns free seats while `SeatPricingPolicy`/`CabinPricingContext` price paid seat choices; `SeatAllocationValidator` and `SeatAvailabilityChecker` guard writes.
- Writes are protected by service authentication: booking-service and checkin-service call it with `ROLE_SERVICE` tokens carrying audience `inventory-service`.
- State changes are published as `InventoryEvent`s (created/held/released/reserved/cancelled); no platform service consumes the topic yet — it exists as an integration point.

## payment-service

| Fact | Value |
|---|---|
| Port | 8086 |
| Management port | 9086 |
| Database | `skybook_payment` (Flyway-managed, `ddl-auto: validate`) |
| Kafka produces | `skybook.payment.events` |
| Kafka consumes | `skybook.booking.events` (group `payment-service`) |
| Key entities | `Payment`, `PaymentTransaction`, `Refund`, `Invoice`, `PaymentHistory` |

Handles payment authorization/capture, refunds and invoicing (`PaymentController`, `RefundController`, `InvoiceController`), reacting to booking events and answering with payment events — it never calls another service over HTTP, which is why it is deliberately absent from auth-service's client registry.

Notable design points:

- **Simulated gateway** (`payment.gateway.simulated: true`): amounts ending `.13` decline authorization and `.31` fail capture — deterministic failure injection for tests and demos. Stripe/Adyen selection is the planned future of this seam.
- **Refunds**: `RefundCalculator` applies the 30% SAVER cancellation fee (`payment.refund.saver-fee-percent`), contractually kept equal to booking-service's quoted number so a passenger is never quoted one amount and refunded another; completion is announced as `REFUND_COMPLETED`.
- Supported currencies (`CurrencyValidator`): USD, GBP, EUR, INR. Invoice and payment reference numbers come from dedicated generators (`InvoiceNumberGenerator`, `PaymentReferenceGenerator`); state transitions run through `PaymentStateMachine`.

## checkin-service

| Fact | Value |
|---|---|
| Port | 8087 |
| Management port | 9087 |
| Database | `skybook_checkin` (Flyway-managed, `ddl-auto: validate`) |
| Kafka produces | `skybook.checkin.events` |
| Kafka consumes | `skybook.booking.events` (group `checkin-service`) |
| Sync dependencies | flight-service, inventory-service (Feign + service tokens) |
| Key entities | `CheckIn`, `BoardingPass`, `Baggage`, `FlightManifest`, `CheckInHistory`, `BoardingPassEmailLog` |

Online check-in, boarding passes, baggage and the flight manifest (`CheckInController`, `BoardingPassController`, `BaggageController`, `FlightManifestController`). Booking `CONFIRMED` events create the check-in rows; `CANCELLED` cascades. Passenger check-in/boarding/no-show and boarding-pass generation are published back as `CheckInEvent`s.

Notable design points:

- **Windows**: check-in opens 24 h and closes 45 min before departure; boarding opens at 45 min and the gate closes at 20 min.
- **Signed boarding passes**: `BoardingPassTokenSigner` HMAC-signs the pass token with `CHECKIN_BOARDING_PASS_KEY` and refuses to boot if the key is missing, shorter than 32 bytes, or the known dev default. Boarding groups come from `BoardingGroupAssigner`, tag numbers from `BaggageTagGenerator`.
- **Baggage allowances** per cabin (kg): economy-saver 15, economy-flexi 20, premium-economy 25, business 32; excess charged at 10 per kg.
- Accepts guest tokens (`accept-guest-tokens: true`) — together with booking-service it forms the guest check-in surface.

## notification-service

| Fact | Value |
|---|---|
| Port | 8085 |
| Management port | 9085 |
| Database | none — stateless |
| Kafka consumes | `skybook.email.events`, `skybook.booking.events`, `skybook.checkin.events` (group `notification-service`) |
| Kafka produces | — |

A pure Kafka consumer with no inbound business API and no database: three listeners (`EmailEventConsumer`, `BookingEventConsumer`, `CheckInEventConsumer`) turn platform events into passenger email.

Notable design points:

- **Rendering pipeline**: HTML email templates (`BookingEmailTemplate`, `CheckInEmailTemplate`), ticket documents as PDF attachments (`TicketPdfRenderer`/`TicketPdfTemplate`), boarding-pass PDFs with QR codes (`BoardingPassPdfTemplate`, `QrCodeGenerator`), enriched with airline and airport-city lookups.
- **SMTP defaults**: `application.yml` points at Gmail (`smtp.gmail.com:587`), but the compose stack overrides this to the local **Mailpit** sink (`mailpit:1025`, web UI at `http://localhost:8025`) so demo mail is actually viewable; real deployments override `SPRING_MAIL_*`. Without the override, placeholder Gmail credentials drop every email silently.
- The actuator **mail health indicator is disabled** — it opens a real SMTP connection, and an unreachable SMTP host would otherwise mark a perfectly healthy consumer DOWN.

## frontend

| Fact | Value |
|---|---|
| Port | 3000 (host-published) |
| Stack | React 19, Vite 8, TypeScript 6.0, Tailwind CSS 4, react-router-dom 7 |
| Tests | Vitest (+ Testing Library), Playwright e2e incl. a `mobile` project |
| Module | `frontend/` (nginx container) |

The passenger-facing SPA. In the container, nginx serves the built static bundle on port 3000 and proxies `/api` to the gateway, so the browser sees a single origin — which is what allows the httpOnly session cookie to be `SameSite=Lax` rather than `SameSite=None`. Public flight search works logged-out; authentication is required only at booking time. Port 3000 was reserved for the frontend when observability deliberately placed Grafana on 3001.

## Shared build modules

| Module | Role |
|---|---|
| `skybook-common` | Cross-service contracts: the Kafka event DTOs and their type enums, `KafkaTopics` constants, `ErrorResponse`, the `Auditable` JPA base, `AirportTimeZones` |
| `skybook-security` | Shared security auto-configuration consumed by every service: `JwtTokenValidator`/`JwtSecurityAutoConfiguration` (verify-only RS256, issuer/audience/guest-token knobs), `ServiceTokenProvider` + Feign interceptors for the client-credential flow, JSON 401/403 handlers |
| `e2e-tests` | Black-box end-to-end suite (REST Assured + Awaitility, run via maven-failsafe) against a running stack |

## Where these services run

Locally and in every pipeline rung the fleet runs as the Docker Compose stack in `docker-compose.yml` (services plus Postgres, Kafka, Mailpit, and the Prometheus/Loki/Tempo/Grafana observability set). GitHub Actions promotes builds through the DEV → SIT → QA → PERF → UAT → STAGING → PROD ladder, with production on an Oracle Cloud VM. Per-service deep-dive design documents live in the repo under `docs/` (e.g. `docs/SECURITY_HARDENING_MODULE.md`, `docs/RESILIENCE_MODULE.md`, `docs/CI_CD_MODULE.md`, `docs/ENVIRONMENTS.md`).
