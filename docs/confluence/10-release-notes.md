# Sprint / Release Notes

This page is the running release history of the SkyBook platform, grouped into thematic releases by the date clusters visible in the `main` branch history. It is written for engineers and reviewers who need to know **what shipped when**, which database migrations each release carried, and what an operator had to do (or watch for) when the release rolled out. Release names are the commit dates of the cluster — SkyBook promotes continuously through the DEV → SIT → QA → PERF → UAT → STAGING → PROD ladder rather than cutting versioned tags. Facts below are taken directly from commit messages and the Flyway migration trees in `backend/*/src/main/resources/db/migration/`.

## Release index

| Release | Theme | Key migrations |
|---|---|---|
| 2026-08-19 → 20 | Account verification & passenger contact | auth `V9__email_verification` |
| 2026-08-18 | Ticketing realism v2 & real India/UK networks | booking `V17__validating_airline` |
| 2026-08-15 → 16 | Ticket document overhaul, taxes, governed doc set | booking `V16__booking_taxes` |
| 2026-08-07 → 08 | Idempotency & mobile hardening | booking `V15__booking_idempotency`, payment `V3__refund_source_reference` |
| 2026-08-05 → 06 | Guest check-in & Sign in with Google | booking `V14__guest_lookup_attempts`, auth `V7`/`V8`, checkin `V5` |
| 2026-08-04 | Environment ladder, quality gate, case studies | — |
| 2026-07-30 → 31 | Round-trip single PNR, through-ticketing, admin console, i18n | booking `V10`–`V13`, checkin `V3`/`V4` |
| 2026-07-24 → 29 | Frontend build-out & E2E certification | — |
| 2026-07-17 → 23 | Security hardening | ownership `owner_subject` migrations (booking `V5`, checkin `V2`, payment `V2`) |
| 2026-07-14 → 17 | Seat selection & seat pricing | booking `V1` baseline, `V2__add_booking_passenger_fare_breakdown` |
| 2026-07-09 → 12 | Platform foundation: gateway, dockerization, CI/CD, observability, resilience | — |

---

## 2026-08-19 → 2026-08-20 — Account verification & passenger contact

**Highlights (user-facing)**

- **OTP email verification at registration** (`6f9583e`). A new account cannot sign in until it redeems the 6-digit code mailed to it; the path is real end-to-end (auth → Kafka `EmailEvent` → notification → SMTP). Login answers 403 (not 401) for a correct password on an unverified account, raised only after the credential check so nothing is enumerated. Resend endpoint always returns 202 with a 60-second cooldown; five wrong guesses returns 429. SSO accounts are born verified, and a Google sign-in matching an unverified password account completes its verification.
- **Wrong-email escape hatch** on the verify step (`f30c037`) and role-aware post-sign-in landing so a stale `/admin` returnTo no longer strands a passenger (`7faf31c`).
- **Mandatory per-passenger email** (`da74efb`): every traveller carries their own address for disruption notices; the field already existed as optional so stored rows and the wire format are unchanged — only creation got stricter. The booker's profile email prefills via the "Myself" pick.
- **Airport full names on ticket documents** (`c547e67`) — e.g. "London Heathrow · Terminal 3" — from one source of truth per side (`AirportCityLookup.nameFor`).
- Admin console polish: DateField tile and aligned "+ New flight" (`3ea4c33`).

**Technical changes**

- OTP codes stored as SHA-256 hashes only, one live code per user; `noRollbackFor` keeps the attempt counter real under `@Transactional` (caught live). An UNVERIFIED email can be re-registered (nobody proved ownership); a VERIFIED one still 409s.
- Every automated journey (e2e suite, smoke, UAT) redeems its code off Mailpit the way a customer would.
- Case study recaptured at v4 with the sticky header pinned and a real boarding pass (`242b3ed`).
- 2026-08-20 follow-ups: the STAGING smoke now reads the rehearsal's Mailpit rather than prod's (`f5816ba`); the payment reference-collision test was de-flaked (`84b1e57`).

**Operational notes**

- Migration: auth-service `V9__email_verification` — adds `users.email_verified` (existing rows grandfathered TRUE) and the `email_verification_otps` table.
- `POST /api/auth/verify-email` and `/api/auth/resend-verification` are public through all gates; the bootstrap admin is verified by promotion because CI has no inbox.
- Live-verified: 20/20 API checks plus the full UI funnel with the code pulled from Mailpit. Rebuilt and redeployed auth-service, booking-service, notification-service and frontend images.

---

## 2026-08-18 — Ticketing realism v2 & real India/UK networks

**Highlights (user-facing)**

- **Airline-data realism on tickets** (`6654253`): booking class (RBD) now derives from cabin + fare brand (First F/A, Business J/C, Premium Economy W/E, Economy S/B/Y) instead of the fare brand's first letter, which had printed Economy Saver's "S" on First itineraries. Premium cabins sell flexible brands only — the quote omits SAVER for First/Business and the booking API rejects the combination. Tickets issue on the validating carrier's real IATA stock (EK 176, BA 125, AI 098, …).
- **Real India domestic network** (`f179f92`): 71 airports (every city with scheduled commercial service), 540 daily departures across 6E/AI/IX/QP/SG, distance-derived durations, ATR 72s on regional hops; the only domestic Business is Air India's two-cabin A320neo on metro trunks. 2026 facts baked in (VTZ transferred to Bhogapuram 17 Aug 2026; Rajkot serves from HSR, not RAJ).
- **Real UK + Crown Dependencies network**: 47 airports, 128 routes, 612 daily departures with frequencies derived from CAA June 2026 Table 12.2 passenger volumes; island metal down to the 8-seat Orkney Islander; route effective windows model real events (Heathrow–Dundee ends 23 Oct 2026, Newquay–Scilly resumes 1 Sep 2026).
- Booking resumes after mid-journey registration; even search tiles; multi-city fare-calendar date fix (`ed09d90`).

**Technical changes**

- `TaxPolicy` carries effective dates: UK APD Band B selects 90/216 or 102/244 by each leg's departure date, never hard-coded to today.
- Both networks are **generator-driven from one editable table each** (`scripts/seed/gen_india_network.mjs`, `gen_uk_network.mjs`), emitting additive, idempotent seeds (`scripts/seed/12_…`–`17_…`) wired into `seed.sh`.
- **Centralised frontend constants layer** (`ffc88d3`): one import site for brand identity, cancellation/check-in policy figures (interpolated into UI copy so dialogs and tickets can never drift from the numbers), baggage allowances, extra-bag fee and printable-document palettes; the client tax mirror is effective-dated to match the backend.
- Full case-study screenshot refresh at 2x with lossless PDFs and a maintained capture pipeline (`63ed5c1`).

**Operational notes**

- Migration: booking-service `V17__validating_airline` — the first marketing carrier is captured on the booking at draft and drives the ticket-stock prefix.
- All 100+ new airport codes must exist in `AirportTimeZones` (the UTC-fallback duration gotcha), the email city lookup and the search typeahead; `refleet.sh` leaves both networks' metal alone.

---

## 2026-08-15 → 2026-08-16 — Ticket document overhaul, taxes, and the governed doc set

**Highlights (user-facing)**

- **The emailed e-ticket became the same document as the on-screen one** (`ebea24a`): the Style C ticket-office ledger, maroon header, deterministic Code-128-style barcode from the PNR, per-segment itinerary with terminals and NVB/NVA/duration rows — replacing a leftover blue layout.
- **Per-departure passenger taxes** on bookings and both ticket documents (`4547c09`).
- Airline names on both documents; goodwill refunds on fee-withheld payments (`939d809`); a connection band between chained flights (`fa964b0`); one-page ticket with a conditions-of-carriage page 2 (`1a8ace3`); balanced margins, uppercase passenger name, bold seats (`8eb71eb`).
- **Visakhapatnam (VTZ)** joined the airport catalog, plus a default-off backdated-booking switch (`6c3f54d`).

**Technical changes**

- `flyskybook.com` became the contact domain on documents (`3d13883`).
- **Governed engineering documentation set** SKB-DOC-00..11 under `docs/enterprise/` (`162260a`), with Word editions and a generator (`2de00e6`).

**Operational notes**

- Migration: booking-service `V16__booking_taxes`.
- VTZ's timezone entry is the important half: durations are computed from airport-local wall clocks, so an unknown airport falls back to UTC — a 1h15m HYD→VTZ hop had published as 6h40m. **Any future airport must land in BOTH the catalog and the timezone table.**

---

## 2026-08-07 → 2026-08-08 — Idempotency & mobile hardening

**Highlights (user-facing)**

- **A retry is now free** (`89b1070`, implementing `docs/IDEMPOTENCY_MODULE.md`). The live defect: a redelivered `PARTIALLY_CANCELLED` event passed the payment status guard and issued a **second refund** and a second gateway call, made routine by the DLT retry policy. Refunds now carry a `source_reference` naming their cause, unique per payment, so a redelivery computes the same value and the insert is refused — the database enforces once-only where the status guard provably could not.
- **The app fits a phone, measured not guessed** (`66d2e87`, `8703032`): six mobile complaints fixed on real viewports, the return-swap button phones never had, menus that fit, and a guest trip card and boarding pass sized for a phone.
- Premium is refundable, and each fare refunds by its own rules (`d8241a5`).
- Buttons look like buttons, links look like links (`b515d29`).

**Technical changes**

- Payment `create()` gained a request fingerprint beside the existing idempotency key: same key + different body is now a 409 instead of silently returning someone else's payment; the SELECT-then-INSERT race loser re-reads and replays the winner. An e2e test certifies a replay resends the SAME body and a mismatch 409s (`0376124`).
- The flights list endpoint was paginated after OOM-ing on ~920k rows (`7be62e2`).
- A Pixel-class mobile viewport project joined the Playwright suite and drifted browser specs were repaired (`98a219a`); public shopping is certified through SEARCH, not the list-all (`d9db53c`).
- An account session now outranks a leftover guest cookie (`db5fdf6`).

**Operational notes**

- Migrations: booking-service `V15__booking_idempotency`, payment-service `V3__refund_source_reference`.
- README rewritten to describe the platform, not just how to start it (`612a050`); platform-evolution and engineering-toolchain addendum (`85fbb97`).

---

## 2026-08-05 → 2026-08-06 — Guest check-in & Sign in with Google

**Highlights (user-facing)**

- **Guest check-in** (`f65acf8`, `20dd201`): retrieve a booking, check in and get a boarding pass **without an account**, via a `/check-in` page — backed by guest sessions, scoped tokens and a deliberately caged API surface. The first live run found three defects, all fixed same-day (`c2a1bcf`), plus: a lapsed guest returns to the lookup form, not `/sign-in` (`64250d2`), and the `__Host-` cookie prefix and the `Secure` flag move together (`9d2a761`).
- **Sign in with Google** (`5d194e7`): OIDC with the token exchanged at auth-service. Two live fixes followed: the pending cookie carries secrets, not configuration (`0ffc91b`), and browser-facing redirects are absolute on the public origin (`ad33793`).
- Refund displays show their own arithmetic from what was actually paid (`eea6ca8`, `db64b89`); the Modify flow no longer drops the contact phone on rebook (`46e8068`).

**Technical changes**

- Both features went through explicit traced-design review before build (guest check-in design v2 dispositioned all 30 round-1 findings, `7d0b242`; SSO design at `ae93f71`). See `docs/GUEST_CHECKIN_MODULE.md` and `docs/SSO_MODULE.md`.
- The STAGING rehearsal was isolated from prod — volumes, ports, stdin (`90a8163`).
- Seed arrivals are now authored destination-local at source; additive reseeds had stayed origin-clock (`dac6b62`).

**Operational notes**

- Migrations: booking-service `V14__guest_lookup_attempts`; auth-service `V7__create_federated_identities` (SSO) and `V8__service_clients_guest_token_grant`; checkin-service `V5__boarding_pass_email_log`.
- Guest sessions must never hit the global 401 → `/sign-in` redirect; outbound calls distinguish service-token vs caller-token.

---

## 2026-08-04 — Environment ladder, quality gate, and case studies

**Highlights (user-facing)**

- Arrival times read on the **destination's clock** (`1299999`); cancelled legs are no longer counted as still-to-fly (`1d1acfb`); password-reset emails no longer link to localhost in production (`0d49027`).
- Mandatory contact phone at booking, with the client-side validation the requirement promised; the network reached India and the US coasts (`d38efe0`, `9364642`).

**Technical changes**

- **The environment ladder** (`32b4833`): LOCAL → DEV → SIT → TEST/QA → PERF → UAT → STAGING → PROD, with a weekly DR drill. One principle carries it — *build once, promote many*: images are multi-arch (free arm64 runners + a stitched manifest) so the same digests run on the x86 ladder rungs and the Ampere production VM. `docker-compose.ladder.yml` swaps `build:` for images; `deploy/environments/*.env` carries each rung's twelve-factor identity. `promote.yml` walks the rungs: DEV smokes the front door, SIT probes cross service boundaries ending with an auth event traversing Kafka into the mail sink, QA runs the e2e certification against the promoted images, PERF gates on k6 thresholds.
- Ladder fixes landed the same day: the DR drill genuinely runs `:latest` (`20a1630`); each rung seeds the environment it is standing in (`0f089ee`); QA admin promotion (`2f94817`).
- **SonarCloud gate passed**: the nine actionable bugs cleared (`191d4b8`, PR #15), coverage-lift merges across booking, notification, auth and security paths, and the CSRF / no-show-sweep suppressions justified in writing (`05ea768`).
- Case studies rebuilt against the passing gate with every screen included, passenger screens photographed as a passenger (`2b93d26`, `9b660bc`).

**Operational notes**

- No new migrations. The arrival-time correction is a data fix; seeders author destination-local times from `dac6b62` onward — do not chain the blanket arrival fix after additive seeds.

---

## 2026-07-30 → 2026-07-31 — Round-trip single PNR, through-ticketing, admin console, i18n

**Highlights (user-facing)**

- **Round trip in ONE PNR**, built in reviewed steps (`5b41f1c` → `69c08ef`): booking segments, IATA-style tickets and coupons, a segment-aware saga taking **one payment across both flights**, a cancellation matrix with Premium per-segment date change, and a frontend that books one PNR and manages segments. Step 7 closed with a live e2e green.
- **Through-ticketing**: same-carrier connections sell as one through-ticket (`4845995`) with per-leg seat selection (`24dc99f`); **multi-city trips** up to three legs on one PNR (`9e11aaa`).
- **Fare watch**: watch a route, get mailed when the fare moves (`278c43e`).
- **Full back-office admin console** — every ADMIN operation plus an ops dashboard shell (`cadda81`).
- Real terminal assignments on every scheduled flight, with departure and arrival terminals on the boarding pass (`d109a23`, `50c4014`, `0e41437`).
- **10 languages including Telugu**, Arabic renders right-to-left, and a display-currency switcher; platform currency is GBP (`c58497f`, `6ff7aaf`, `7297247`).
- Fare-family entitlements: free seats on Flexi/Premium, Premium date changes (`87a65a2`); account preferences, profile hub, saved-traveller quick-fill (`84eebb7`).

**Technical changes**

- Skyscanner-density full-mesh seed schedule — every pair, 3× daily, install-date + 1 year (`619aea5`); departed and imminent flights can no longer be booked (`78647e6`).
- FLOWN coupon sweep retires used coupons at the record level (`a0c1290`).
- Design doc: `docs/ROUND_TRIP_MODULE.md` (two review rounds before build).

**Operational notes**

- Migrations: booking-service `V10__booking_segments`, `V11__tickets_and_coupons`, `V12__booking_segment_direction`, `V13__fare_alerts`; checkin-service `V3__add_terminals`, `V4__boarding_pass_arrival_terminal`.
- Step 8 of the round-trip plan (dropping the deprecated mirror columns) was deliberately deferred to a later release.

---

## 2026-07-24 → 2026-07-29 — Frontend build-out & E2E certification

**Highlights (user-facing)**

- **The React/Vite/TypeScript frontend shipped**, on `feature/frontend` (merged as PR #10): scaffold → API client with httpOnly session-cookie auth (`d6cb0a4`) → register/sign-in → search → fare quote → seat map → passenger details, payment, confirmation → my bookings, check-in and boarding pass — then public search with airport typeahead, password reset and remember-me (`cbdb486`, `6050e54`), a carrier-style multi-step booking flow (`db14bad`), a full IATA-style boarding pass with scannable QR (`c217f2a`), a Qatar-style e-ticket receipt, multi-passenger booking, passenger-level cancellation with the guardian rule, profile + saved travellers, an admin console module, and successive design passes landing on an Etihad-sampled design language (`6633ef2`).
- Demand-shaped fares with a genuine per-date fare calendar (`b4cd2bc`); 1-stop and 2-stop itineraries with detailed layovers (`02dba5a`).

**Technical changes**

- **E2E certification module** (PR #9, `docs/E2E_CERTIFICATION_MODULE.md`): happy-path spine (search → quote → book → pay → confirm), check-in → boarding pass → boarded, real captured-email assertions via an SMTP sink, a failure matrix (decline, idempotency, cancellation, authz), service-down degradation and recovery, a double-sell concurrency race, and a distributed-trace assertion across the Kafka hop — 32 assertions across 9 classes, with a one-command entry point and a nightly workflow.
- Frontend containerised with nginx and a separate CI workflow (`d47f0dc`); Mailpit became the default mail sink so confirmations actually arrive locally (`54fbb7d`).

**Operational notes**

- No new backend migrations in this window; `customerId` became optional on booking to serve the public funnel (`63c7b98`). Seed scripts were made re-runnable (FK-ordered deletes, seeding from `routes.json`).

---

## 2026-07-17 → 2026-07-23 — Security hardening

**Highlights (user-facing)**

- No visible feature change — this release is why later features (guest tokens, SSO, the admin console) could be built on a sound authorization model.

**Technical changes**

- Design frozen at 10/10 after five review rounds (`docs/SECURITY_HARDENING_MODULE.md`), then built in 14 ordered steps and merged as PR #8: the `skybook-security` auto-config module with an RS256 validator; RS256 issuance and gateway migration; a client-credential registry with a `/service-token` endpoint; an ownership schema propagated on events; a Feign identity split (service-token vs caller-token per operation); the authz matrix enforced service-by-service across inventory, payment, checkin, booking, flight and notification, ending deny-by-default.
- Runtime hardening: actuator moved to an internal-only management port, internal container ports unpublished, committed secret defaults removed with fail-fast on unset, non-root read-only container runtime.
- Supply chain: Trivy dependency and image scanning, scan-before-push, gated on HIGH/CRITICAL; CVE-driven pins (bcprov 1.84, jackson 2.21.5, commons-lang3 3.18.0).
- Pre-merge review closed fail-closed gaps: RS256 `exp`, a SERVICE→booking IDOR, keypair handling, token fetch, Kafka identity, committed defaults (`2a06510`).

**Operational notes**

- Migrations: the `owner_subject` ownership columns (booking-service `V5__add_owner_subject`, checkin-service `V2__add_owner_subject`, payment-service `V2__add_owner_subject`; auth-service `V3__create_service_clients`).
- Prometheus scrapes tokenless via the permitted `/actuator/**` on the management port.

---

## 2026-07-14 → 2026-07-17 — Seat selection & seat pricing

**Highlights (user-facing)**

- Free auto-assigned seats with **paid seat surcharges** for chosen seats: the seat map surfaces each seat's listed surcharge, the booking flow moved to draft → hold → finalize, and check-in gained a contained seat-change rule.

**Technical changes**

- `SeatPricingPolicy` core in inventory-service; atomic auto-hold with a pessimistic flight lock and a **hold-time pricing snapshot** (money-level idempotency — the price you held is the price you pay); cabin availability + fare quote; payment/invoice charge aggregates. Merged via PR #7 after seven design review rounds and PR-review hardening (`docs/SEAT_SELECTION_MODULE.md`).

**Operational notes**

- Migrations: booking-service Flyway `V1` baseline plus `V2__add_booking_passenger_fare_breakdown` (this release introduced Flyway to booking-service).
- Seed data spans two Postgres databases (booking and inventory) — both must be seeded together or seat maps and fare breakdowns disagree.

---

## 2026-07-09 → 2026-07-12 — Platform foundation: gateway, dockerization, CI/CD, observability, resilience

**Highlights (user-facing)**

- One command starts the whole platform: `docker compose up --build` (`cee16c8`).

**Technical changes**

- **API gateway** (`docs/API_GATEWAY_MODULE.md`): static routing, request logging and JWT enforcement filters, CORS, rate limiting, downstream error handling with a full test suite.
- **Dockerization** (`docs/DOCKERIZATION_MODULE.md`), including the single-broker Kafka fix where `__consumer_offsets` was never actually created (`2384ac3`).
- **CI/CD** (`docs/CI_CD_MODULE.md`): GitHub Actions workflow, surefire/failsafe split with JaCoCo rebinding, Testcontainers-backed context-load tests, SonarCloud coordinates in the root pom.
- **Observability** (`docs/OBSERVABILITY_MODULE.md`): metrics, centralized logs and distributed tracing (`d6802e9`).
- **Resilience** (`docs/RESILIENCE_MODULE.md`): circuit breakers, bulkheads and read retries on inter-service clients; explicit Feign timeouts (2s connect / 5s read); Kafka bounded retries with dead-letter topics on all four consumers; send-failure visibility on all five event producers; a Grafana resilience row.
- Check-in service completed its full testing pyramid (150 tests) and boarding-pass email with inline QR + PDF landed in the same window (PR #6).

**Operational notes**

- Each module's design was drafted, reviewed and frozen before implementation — the pattern every later feature on this page followed.

---

*History source: `git log` on `main`. For the deeper design record behind any release, see the corresponding module document under `docs/` named in each section.*
