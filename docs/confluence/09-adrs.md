# Architecture Decision Records

This page is the canonical index of SkyBook's architecture decisions, reconstructed from the module design documents in `docs/` (each of which recorded its decisions and review revisions at the time), from code-level design comments, and from the git history. It exists so that engineers joining the platform — or revisiting a subsystem months later — can see *why* the system is shaped the way it is without re-deriving it from code. Each record follows the standard Context / Decision / Consequences form; the linked module document is the authoritative long-form design. Dates are the dates the decision landed on `main` (or was frozen in design review) per `git log`.

## Index

| ADR | Title | Date | Status |
|---|---|---|---|
| [ADR-001](#adr-001) | Single Postgres container, one database per service | 2026-07-10 | Accepted |
| [ADR-002](#adr-002) | Event-driven saga over Kafka, not synchronous orchestration | 2026-06-29 | Accepted |
| [ADR-003](#adr-003) | Seat selection: free auto-assign vs. paid manual surcharge | 2026-07-14 | Accepted |
| [ADR-004](#adr-004) | JWT RS256, per-service credentials, audience model | 2026-07-17 | Accepted |
| [ADR-005](#adr-005) | Single PNR for round trips: segments on one booking | 2026-07-30 | Accepted |
| [ADR-006](#adr-006) | Promotion by digest through a ladder, not environment branches | 2026-08-04 | Accepted |
| [ADR-007](#adr-007) | SSO accounts are born verified; verified email is the linking predicate | 2026-08-05 | Accepted |
| [ADR-008](#adr-008) | Guest check-in is a real session type, scoped to one booking | 2026-08-06 | Accepted |
| [ADR-009](#adr-009) | Idempotency keys on booking and payment writes | 2026-08-07 | Accepted |
| [ADR-010](#adr-010) | Two ticket documents, deliberately kept as rendering twins | 2026-08-16 | Accepted |
| [ADR-011](#adr-011) | Effective-dated tax policy, selected by departure date | 2026-08-18 | Accepted |
| [ADR-012](#adr-012) | Validating-airline ticket stock captured at booking draft | 2026-08-18 | Accepted |
| [ADR-013](#adr-013) | Honest cabin availability from generated real-network seed data | 2026-08-18 | Accepted |
| [ADR-014](#adr-014) | OTP email verification: unverified-account takeover, `noRollbackFor` counter | 2026-08-19 | Accepted |

---

<a name="adr-001"></a>
## ADR-001 — Single Postgres container, one database per service

**Date:** 2026-07-10 · **Status:** Accepted · **Source:** `DOCKERIZATION_MODULE.md`, `docker/postgres/init-databases.sql`

**Context.** SkyBook runs eight Spring Boot applications on one docker compose stack, ultimately hosted on a single Oracle Cloud VM. Full "database per service" isolation normally means one database *server* per service — six Postgres containers (one per database-owning service) on one small VM.

**Decision.** Run **one** Postgres container, and give every service **its own database** on that instance: `skybook_auth`, `skybook_flight`, `skybook_booking`, `skybook_inventory`, `skybook_payment`, `skybook_checkin`, created by an `docker-entrypoint-initdb.d` init script on first boot. Each service's datasource URL points only at its own database; no service can see another's tables.

**Consequences.** Logical isolation (no cross-service joins, no shared schema coupling) is preserved while memory and operational cost stay within one VM's budget. The single container is a shared failure domain and a shared resource pool — acceptable for this deployment scale, and services could later be pointed at separate instances without code change since isolation is already at the database boundary. Backup/restore (see the DR drill in `05-environment-guide.md`) operates on one instance.

<a name="adr-002"></a>
## ADR-002 — Event-driven saga over Kafka, not synchronous orchestration

**Date:** 2026-06-29 · **Status:** Accepted · **Source:** `ARCHITECTURE.md` §5–6, `skybook-common` `KafkaTopics.java`, `ROUND_TRIP_MODULE.md` §2

**Context.** A booking touches flight, inventory, payment, check-in and notification concerns. Blocking the customer's request on every downstream write would couple availability of the whole fleet to its slowest member.

**Decision.** Use Kafka when "a business event has happened and other services need to react without blocking the original transaction." Topic constants live in `skybook-common`: `skybook.booking.events`, `skybook.payment.events`, `skybook.checkin.events`, `skybook.inventory.events`, `skybook.flight.events`, `skybook.email.events`. The booking create flow is a compensating saga: draft → per-passenger seat holds at inventory (with compensation on failure) → finalize in one transaction → publish `BookingEvent`. Check-in state flows back onto the booking as a *mirror* via `CheckInEvent`, not a synchronous call.

**Consequences.** Notification, payment reaction and check-in provisioning are asynchronous and independently retryable; the customer's transaction commits locally. The cost is eventual consistency and redelivery: consumers must tolerate duplicates — which is exactly what forced ADR-009. A transactional outbox is a known, deliberately deferred increment.

<a name="adr-003"></a>
## ADR-003 — Seat selection: free auto-assign vs. paid manual surcharge

**Date:** 2026-07-14 (design; merged via PR #7) · **Status:** Accepted · **Source:** `SEAT_SELECTION_MODULE.md` (frozen 10/10 after seven review rounds)

**Context.** Cabin classes existed in the data but there was no assignment algorithm, no surcharge model, and bookings showed whatever *today's* pricing config said rather than what was actually charged.

**Decision.** Model seats the way real airlines do: a passenger who doesn't care gets a low-demand seat **auto-assigned for free** (atomic in inventory-service, under the same per-flight pessimistic lock as manual holds); a passenger who wants a specific seat **pays a per-seat surcharge** priced by attribute (window, exit row, front-of-cabin), on top of the cabin base fare. The review's central addition: a **persisted, immutable fare breakdown per passenger** (baseFare, seatSurcharge, baggage) with *listed vs. charged* distinguished, plus a real `DRAFT` booking status finalized to `CREATED`. Inventory is the sole live-seat authority; booking keeps historical snapshots only.

**Consequences.** A booking always prints what it charged, robust to config changes. Seat holds are money-idempotent (snapshots at creation, replays return stored values, mode-mismatch replays 409). Enforcement applies at booking *and* check-in via a surcharge-entitlement ceiling. The `DRAFT` status this ADR introduced became the anchor point for ADR-012's stock capture.

<a name="adr-004"></a>
## ADR-004 — JWT RS256, per-service credentials, audience model

**Date:** 2026-07-17 (design frozen 10/10 after five reviews; implemented and live-certified 23/23) · **Status:** Accepted · **Source:** `SECURITY_HARDENING_MODULE.md`

**Context.** A valid JWT was checked in exactly one place — the API gateway — and every downstream `SecurityConfig` ended in `.anyRequest().permitAll()` while compose published every internal port. Direct calls to a service port, or a forged `X-Auth-User` header, bypassed authentication entirely. There were no roles at all.

**Decision.** Every service validates the JWT itself, using **RS256** with a verify-only public key — only auth-service holds signing material. Service-to-service calls use **per-service client credentials** with per-operation Feign identity (frozen at the client-interface level), and a **two-rule audience model** constrains which services accept which tokens. Token type ↔ role coherence is enforced by the shared validator (`USER` → exactly `{ROLE_USER}` or `{ROLE_ADMIN}`; `SERVICE` → exactly `{ROLE_SERVICE}`). Internal ports are unpublished; actuator moves to an internal-only management port; containers run non-root on a read-only rootfs.

**Consequences.** Defense in depth replaces perimeter-only trust: a compromised network position no longer equals a compromised fleet. Key rotation touches one signer. The strict validator forced later token types (guest, ADR-008) to become first-class enum values rather than ad-hoc claims — a constraint that proved to be a feature.

<a name="adr-005"></a>
## ADR-005 — Single PNR for round trips: segments on one booking

**Date:** 2026-07-30 (steps 1–7 live-verified) · **Status:** Accepted · **Source:** `ROUND_TRIP_MODULE.md`

**Context.** `Booking.flightId` allowed exactly one flight per booking, so a round trip meant two PNRs, two payments, two confirmations — unlike how a single carrier actually tickets (reference case: a BA LON–HYD round trip is one reference).

**Decision.** One PNR, one payment, one confirmation, with outbound and return as **segments** inside one booking: a new `booking_segments` table (`segment_index` 0 = outbound, 1 = return) and `BookingPassenger` becomes *per passenger per segment*. Everything the passenger row already held — fare breakdown, seat, check-in mirror, cancelled flag — is genuinely per-direction, so the existing machinery transferred without semantic change; checkin-service needed **no schema change** because its unique `booking_passenger_id` now identifies a passenger-on-a-segment. One e-ticket coupon per segment; deprecated flat mirrors (`bookings.flight_id`, flat event fields) shipped as a one-release compatibility window, removed in the following release by design.

**Consequences.** Per-direction check-in, boarding passes, and cancellation (guardian rule, `PARTIALLY_CANCELLED` derivation, refund = stored fares) all came for free from existing invariants (`totalFare = Σ fare over all rows`). The model is deliberately open to multi-city (>2 segments); v1 keeps one cabin + fare family per booking.

<a name="adr-006"></a>
## ADR-006 — Promotion by digest through a ladder, not environment branches

**Date:** 2026-08-04 · **Status:** Accepted · **Source:** `ENVIRONMENTS.md`, `CI_CD_MODULE.md`; see `04-cicd-release-process.md` and `05-environment-guide.md`

**Context.** The platform ships from one repo to one prod VM. Environment branches (`develop`/`release`/`prod`) invite rebuild-per-environment, and a rebuilt artifact is not the artifact that was tested.

**Decision.** **Build once, promote many.** `ci.yml` builds every image once per commit; from that moment nothing is ever rebuilt — `promote.yml` walks the *same image digests* up the ladder **DEV → SIT → TEST/QA → PERF → UAT → STAGING → PROD**, with `docker-compose.ladder.yml` as the single overlay. Each rung answers one question with one gate (smoke, cross-service probes, full e2e certification, k6 thresholds, a human UAT approval, a rehearsal on the real VM). PROD deploys by **pulling digests, never by building**, and ends with a post-deploy backup. Rollback is the same ladder pointed at the last good commit's tag.

**Consequences.** What reaches PROD is bit-identical to what passed QA and PERF, on the same arm64 architecture as the VM. There are no environment branches to drift or merge. The serial rung sequence *is* the ladder on free-tier runners — parallelism would buy speed, not rigor. A weekly DR drill (Monday 04:00 UTC) proves the backups restore.

<a name="adr-007"></a>
## ADR-007 — SSO accounts are born verified; verified email is the linking predicate

**Date:** 2026-08-05 · **Status:** Accepted · **Source:** `SSO_MODULE.md` (decision D1), `SsoAccountService.java`

**Context.** "Sign in with Google" (OIDC, token exchanged at auth-service) must decide what to do when the Google identity's email matches an existing password account, and whether SSO-provisioned users need the email verification flow.

**Decision.** `email_verified == true` from Google is the **load-bearing predicate**: an unverified Google email is rejected outright (`sso_email_unverified`) because trusting it would let anyone claim an address they don't own. On that predicate, a Google sign-in **auto-links** to an existing password account with the same verified email (industry default — the interstitial alternative defends against an attacker who already controls the Google account, a compromise that loses the account via forgot-password anyway). Newly provisioned SSO users are created with `emailVerified = true`, and a Google sign-in flips a legacy unverified row to verified — Google has already proven address ownership.

**Consequences.** SSO users never see the OTP flow (ADR-014); one email means one account regardless of sign-in method. The rejection branch is the entire cost of the guarantee, and it is enforced at the auth-service, not the browser.

<a name="adr-008"></a>
## ADR-008 — Guest check-in is a real session type, scoped to one booking

**Date:** 2026-08-06 (design frozen; live-fixed through 2026-08-08) · **Status:** Accepted · **Source:** `GUEST_CHECKIN_MODULE.md`, `GuestSessionController.java`, `frontend/src/api/guest.ts`

**Context.** A passenger booked by a travel agency has no SkyBook login but must retrieve the booking, check in, and get a boarding pass. The industry-standard credential — booking reference + last name — is deliberately weak, so its blast radius must be capped.

**Decision.** "A guest session is a session — in its own cookie, with its own token type, admitted only by services that opted in, scoped to one booking, and answered with `404` everywhere outside that scope." Concretely: a **`__Host-skybook_guest`** httpOnly cookie (deliberately not the account cookie, so a guest lookup never evicts a logged-in session; the `__Host-` prefix makes Secure + host-only + no-Path browser-enforced); a `GUEST` token type in the shared validator with coherence rule exactly `{ROLE_GUEST}`; an `accept-guest-tokens` property **default `false`** so unopted services reject guest tokens outright. Money actions (cancel, modify, paid seats, invoices) stay with the owning account. On the frontend, guest calls set `silent401` — a guest has no account, so they are **never** redirected to `/sign-in` by the global 401 rule; an account session outranks a leftover guest cookie.

**Consequences.** The weak credential buys strictly less than a login. Two of the three hard-won live fixes are now doctrine: `__Host-` without `Secure` is a cookie no browser stores, and a lapsed guest must land on the lookup form, not the sign-in page.

<a name="adr-009"></a>
## ADR-009 — Idempotency keys on booking and payment writes

**Date:** 2026-08-07 (design 2026-08-06) · **Status:** Accepted · **Source:** `IDEMPOTENCY_MODULE.md`

**Context.** `POST /api/bookings` had no dedupe at all: the PNR is server-generated at random, so a retried request looked brand new — one human retry produced two bookings, two seat-hold sets, and (because payment's own dedupe keys on `bookingId`) **two charges**. Payment already had an `Idempotency-Key` precedent, but with holes, and the frontend never sent the documented header.

**Decision.** "A retry must be free." Every money-adjacent write — booking creation, payment creation/authorize/capture/refund — takes a client-supplied idempotency key stored unique (`payments.idempotency_key`; booking carries `idempotencyKey` plus a request **fingerprint**). A replay returns the *original outcome* (`200` vs the original `201`); a reused key with a different fingerprint is a `409`, never a silent second write. The frontend passes the key on every such call, and the one Kafka path that double-charged on redelivery dedupes too. The transactional outbox remains a separate, later increment.

**Consequences.** Double-clicks, lost responses and Kafka redeliveries stop being financial events. The e2e suite enforces the contract's sharp edge: a replay must resend the *same* body, and a mismatch must `409`.

<a name="adr-010"></a>
## ADR-010 — Two ticket documents, deliberately kept as rendering twins

**Date:** 2026-08-16 · **Status:** Accepted · **Source:** `frontend/src/features/bookings/printable.ts`, `notification-service` `TicketPdfTemplate.java`

**Context.** The passenger gets an e-ticket two ways: a browser-side printable document downloaded from the booking page, and a PDF attached to the confirmation email by notification-service. Serving the email's PDF to the browser would mean faking a download or adding a cross-service call for a document the frontend can already render.

**Decision.** Keep **two implementations of one design** — the user-chosen "Style C ticket-office ledger" — as deliberate twins: the client printable in `printable.ts` and the server PDF in `TicketPdfTemplate.java`, whose header comment declares it "the SAME design as the frontend's downloadable e-ticket." Shared details are mirrored point-for-point and annotated on both sides: city lookup (`cityFor()`), spaced day-month-year with a locale-proof month array, the Code-128-style barcode strip, per-cabin baggage defaults, the conditions-of-carriage page 2.

**Consequences.** No cross-service fetch on the passenger path and no fake download — but the twin invariant is a standing maintenance contract: **every visual or data change must land in both files in the same commit** (the git history shows exactly this pattern: airline names, taxes, airport names each touched "both documents"). `printable.test.ts` pins the client side.

<a name="adr-011"></a>
## ADR-011 — Effective-dated tax policy, selected by departure date

**Date:** 2026-08-18 (per-departure taxes 2026-08-16) · **Status:** Accepted · **Source:** `booking-service` `TaxPolicy.java` + `TaxPolicyTest.java`

**Context.** Government and airport taxes are assessed per passenger per departure, and real tax rates change on fiscal-year boundaries — UK Air Passenger Duty Band B rises on **1 April 2026**. A tax table hard-coded to "now" mis-prices any booking whose departure crosses the boundary.

**Decision.** `TaxPolicy` is a pure, no-I/O component whose rates **carry effective dates and are selected by the leg's departure date** — never by the booking date. Codes follow ticketing convention (GB/UB are the real IATA codes for the UK pair): GB APD Band B £90.00 economy / £216.00 other cabins up to 31 Mar 2026, £102.00 / £244.00 from 1 Apr 2026; UB Passenger Service Charge £29.10; AE £16.30 (DXB); IN UDF & K3 £13.60; XT £11.20 elsewhere. The booking **stores the merged breakdown**, so tickets print exactly what was charged; a `booking.taxes.enabled` switch lets unit fixtures construct the policy disabled.

**Consequences.** A March and an April departure on the same PNR price differently and correctly, and a later rate change can never rewrite an issued ticket. Amounts are demo-realistic 2026 figures, not a live tax feed — swapping in a feed would change the source, not the model.

<a name="adr-012"></a>
## ADR-012 — Validating-airline ticket stock captured at booking draft

**Date:** 2026-08-18 · **Status:** Accepted · **Source:** `booking-service` `BookingServiceImpl.java` (V17 migration)

**Context.** E-ticket numbers begin with the validating carrier's IATA accounting prefix (its "ticket stock" — EK issues on 176, BA on 125). Deriving the prefix at render time from the flight would let a later flight change silently renumber an already-issued ticket.

**Decision.** Capture `validatingAirline` **on the booking row at draft creation**, derived from the first marketing carrier of the journey, and issue ticket numbers from a fixed stock map — EK 176, BA 125, AI 098, 6E 312, SG 775, EI 053 — with anything unknown (or any pre-V17 booking) issuing on the legacy SkyBook stock 125. The ticket number is `stock + zero-padded booking id + traveller index` (`String.format("%s%08d%02d", ...)`).

**Consequences.** Ticket numbers are stable for the life of the booking and match what a real validating-carrier itinerary would show. Historic bookings need no backfill: the null-means-125 fallback makes the migration additive. The capture point (draft) is the same place ownership and the idempotency key are snapshotted, keeping "everything immutable about a booking is set at draft" a single rule.

<a name="adr-013"></a>
## ADR-013 — Honest cabin availability from generated real-network seed data

**Date:** 2026-08-18 · **Status:** Accepted · **Source:** `SEED_DATA.md` (India + UK network sections)

**Context.** Hand-written demo seed data drifts into impossibilities — Business cabins on aircraft that don't have them, routes no carrier flies. Once seat selection (ADR-003) prices cabins for real, a fictional cabin is a pricing bug, not a cosmetic one.

**Decision.** Seed the schedule from **generators driven by real network data**, with cabins **honest per hull**: India covers every city with scheduled commercial service (71 airports, ~540 daily departures; metro trunks 3× daily), where ATR 72s and every LCC narrow-body are all-economy and the only domestic Business is Air India's two-cabin A320neo on metro trunks; the UK network is CAA-derived (47 airports, 128 routes, verified carrier/cabin reference), including BA's two-class A320 (Club Europe 12J + 150Y) and all-economy CityFlyer E190s. A regional hop can therefore **never sell a cabin the aircraft doesn't have**. Arrival times are authored destination-local at the source.

**Consequences.** Search results, fares and seat maps stay physically plausible without per-row curation, and reseeding is reproducible from the generators. The constraint is honest scope: the network is only as current as its reference data, and additions must come through the generator, not hand-edits.

<a name="adr-014"></a>
## ADR-014 — OTP email verification: unverified-account takeover and the `noRollbackFor` counter

**Date:** 2026-08-19 · **Status:** Accepted · **Source:** `auth-service` `AuthService.java`

**Context.** Registration must prove address ownership, without letting one abandoned sign-up squat on an email forever, and without the attempt limiter being cosmetic.

**Decision.** Registration mints a 6-digit OTP (stored as a SHA-256 hash) and mails it; the account activates only on redemption. Two deliberate sharp edges: (1) **unverified-account takeover** — a verified account is claimed property (`409`), but re-registering over an *unverified* row simply replaces its name and password and sends a fresh code, since nobody ever proved they own that address; (2) the redeem method is `@Transactional(noRollbackFor = {InvalidVerificationCodeException, TooManyVerificationAttemptsException})` — these exceptions are the method's *normal answers*, and letting them roll back would silently discard the `attempts` increment, leaving the code guessable forever (caught live, not in mocks). All failure shapes return the same generic `400` (no enumeration); already-verified redemption is a quiet success; the welcome email sends only after verification. A concurrent double-register race is translated to the same generic `409` by the unique index.

**Consequences.** The 6-digit space becomes a capped-attempt lottery rather than a brute-forceable one, real owners can always claim their own address, and (with ADR-007) every account in the system has a proven email. Verified 20/20 in live checks, with CI redeeming real OTPs from the Mailpit sink.
