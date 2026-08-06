# 🎫 SkyBook Guest Check-in — Retrieve, Check In, Boarding Pass Without an Account — Design

## Project Information

| | |
|---|---|
| **Author** | Praveenreddy Somireddy |
| **Status** | FROZEN 2026-08-06 — v2 dispositions accepted after review round 1 (§13); build order in flight |
| **Scenario** | A travel agency books on its own SkyBook account and sends the passenger the e-ticket. The passenger has no SkyBook login — and must still be able to retrieve the booking, check in online, get the boarding pass, and have it emailed to an address they choose. |
| **Depends on** | SECURITY_HARDENING_MODULE.md (RS256, gateway translation point, SecurityAccess ownership) · the check-in platform (CHECKIN_SERVICE_MODULE.md) |
| **Explicitly out of scope** | Cancelling, modifying, paid seats/ancillary purchases, invoices, payment data — money actions stay with the owning account · an "agent" role or agency portal · SMS delivery · exit-row / accessibility seat eligibility rules (no such model exists platform-wide; guests inherit exactly the owner's seat semantics, §4) |

# 1. Overview

One sentence governs the design:

> **A guest session is a session — in its own cookie, with its own token
> type, admitted only by the services that opted in, scoped to one booking,
> and answered with `404` everywhere outside that scope.**

This is the industry-standard "Manage My Booking" pattern: the booking
reference is the identifier, the last name is the proof, and the blast
radius of that deliberately weak pair is capped at check-in — which is why
every real airline accepts it. The revision's theme (§13): the weak
credential now buys strictly less — a narrower token, a separate cookie, a
default-deny acceptance model, and abuse counters that survive more than
one service instance.

# 2. Load-Bearing Findings (traced against the live code, not assumed)

## 2.1 The ownership machinery is two guards and one static method

Every check-in ownership decision funnels through
`CheckInAccessGuard.requireOwnerOfCheckIn/requireOwnerOfBooking`
(checkin-service) and `BookingAccessGuard.requireOwnerOfBookingByReference`
(booking-service), both delegating to
`SecurityAccess.requireOwnerOrAdmin(ownerSubject)` (skybook-security), which
compares the token subject to the entity's snapshotted `owner_subject`.
**Extending ownership means extending one method and passing one extra
argument at the guard call sites.**

## 2.2 The shared validator is strict by design — guest must be a first-class type

`JwtTokenValidator` rejects any `token_type` outside the `TokenType` enum
(`user`, `service`) and enforces **type↔role coherence** (USER → exactly
`{ROLE_USER}` or `{ROLE_ADMIN}`; SERVICE → exactly `{ROLE_SERVICE}`).
`GUEST` becomes the third enum value with its own coherence rule (exactly
`{ROLE_GUEST}`) — and, mirroring the existing `accept-service-tokens`
knob, a new **`accept-guest-tokens` property, default `false`** (§3.4):
a service that has not deliberately opted in rejects guest tokens at the
validator, before any endpoint logic runs.

## 2.3 booking-service's blanket rule is the guest's cage — and checkin-service gets the same cage

booking-service ends its chain with `.anyRequest().hasAnyRole("USER",
"ADMIN")`: a `ROLE_GUEST` token gets **403 on every booking endpoint by
default**, and guest access opens per endpoint, by name. checkin-service
today ends with `.anyRequest().authenticated()` — which would silently
admit guests to every *future* endpoint. It therefore adopts the same
shape: the guest-permitted endpoints are enumerated
`hasAnyRole("USER","ADMIN","GUEST")`, and the chain ends
`.anyRequest().hasAnyRole("USER","ADMIN")`. Absence of a rule now means
"no guests", in both services, forever.

## 2.4 The boarding-pass email already exists — this feature re-aims it

notification-service's `CheckInEventConsumer` already turns a
`BOARDING_PASS_GENERATED` event into an HTML email with an inline QR and a
PDF attached, sent to the event's `contactEmail` (the agency's address, in
this scenario). The guest feature adds a re-send with the passenger's
chosen address — consumer, templates, QR and PDF pipeline reused untouched.

## 2.5 The retrieval endpoint exists — and the guest path stops using the PNR after issuance

`GET /api/bookings/reference/{pnr}` exists and is owner-guarded. The guest
**issuance** request is the only place the reference travels (in a POST
body, not a URL); the issuance response returns the numeric `bookingId`,
and every subsequent guest call uses id-based URLs — so the reference never
sits in access logs, and the token never carries it (§3.1).

## 2.6 Check-in windows, seat changes, baggage are all server-enforced — guests inherit exact parity

`CheckInValidator` enforces open/close windows (24 h → 45 min before
departure, on the departure airport's clock), seat-change state rules
(OPEN/CHECKED_IN only), and baggage state rules (CHECKED_IN only) inside
the service. Guests hit the same endpoints and the same validators —
**no new privileges, no new laxity**. Two parity facts are verified as
build-order steps rather than assumed (§10 step 4): that the check-in
seat-change path cannot select surcharged seats without payment for owners
today (guests inherit whichever answer is true), and that baggage
registration is manifest data with no chargeable path (extra-bag purchases
live in booking modify, which is caged).

## 2.7 The check-in UI is embedded in an authenticated page

The passenger check-in flow lives inside `BookingDetailPage` (route under
`RequireSession`), with `BoardingPassCard` and the printable-pass helper as
standalone pieces. The guest page reuses the standalone pieces; the deep
page stays untouched.

## 2.8 One pre-existing wart, fixed in passing

`CheckInAccessGuard.requireOwnerOfBooking` **skips the ownership check when
the booking has no check-in rows**. The guard gains the booking-match
clause and loses the skip.

## 2.9 Identity headers: the gateway must overwrite, not merely add

Downstream services never read `X-Auth-User` as identity (trace-confirmed;
they re-validate the JWT), so a spoofed inbound header is inert — but it
would still pollute logs. Build step 5 makes the gateway **strip/replace**
any inbound `X-Auth-User` rather than append its own alongside. Also
verified rather than assumed: Spring Security's default header writers
already stamp `Cache-Control: no-cache, no-store, max-age=0` on
authenticated responses — a test pins it for the booking and boarding-pass
endpoints (§9), since shared-computer caching is part of this feature's
threat model.

# 3. The Flow

```
Browser                    Gateway                booking-service            auth-service
  |  POST /api/bookings/guest-session {bookingReference, lastName}             |
  |─────────────────────────►|──────────────────────►|                         |
  |                          |   verify (normalized surname, active passenger) |
  |                          |   + per-reference attempt counter (§6)          |
  |                          |                       |  POST /api/auth/guest-token
  |                          |                       |  (HTTP Basic, its own   |
  |                          |                       |   service credential)   |
  |                          |                       |──────────────────────►  |
  |                          |                       |   RS256 guest token ◄───|
  |◄─────────────────────────|◄──────────────────────|  200 {bookingId}        |
  |                          |                       |  + Set-Cookie:          |
  |                          |                       |  __Host-skybook_guest   |
  |  ...from here, id-based traffic with the guest cookie:                     |
  |  GET /api/bookings/{id}                → the booking (view)                |
  |  GET /api/checkins/booking/{id}        → check-in rows                     |
  |  PATCH /api/checkins/{id}/checkin      → check in (window rules apply)     |
  |  PATCH /api/checkins/{id}/seat         → seat change (owner parity)        |
  |  GET /api/boarding-passes/checkin/{id} → the pass (QR on screen, download) |
  |  POST /api/boarding-passes/checkin/{id}/email {email} → PDF to that inbox  |
  |  DELETE /api/bookings/guest-session    → done: cookie expired              |
```

## 3.1 The guest token

Minted by auth-service via `POST /api/auth/guest-token` on the existing
client-credential chain (HTTP Basic, unrouted from the public edge, same
storage/rotation/transport posture as every service credential —
SECURITY_HARDENING_MODULE.md §3.3/§10 governs it; nothing new is invented
here):

| Claim | Value | Why |
|---|---|---|
| `sub` | `guest:<bookingId>` | **Numeric id, never the reference** — subjects flow into `X-Auth-User`, access logs and traces, and the reference is half the credential. `guest:41` identifies without arming. |
| `token_type` | `guest` | Third `TokenType`; coherence rule: exactly `{ROLE_GUEST}` |
| `roles` | `["ROLE_GUEST"]` | Caged by both services' blanket rules (§2.3) |
| `aud` | the user audience (`skybook-api`) | Accepted only where `accept-guest-tokens=true` (§3.4) |
| `booking_id` | the booking's numeric id | The scope: the ONE booking this session may touch |
| `exp` | **30 minutes** | A check-in errand. Not revocable before expiry — stated, not hidden (§8.1) — so the window is kept short and the state machines are the safety net (§8.2). |

Only clients flagged `may-issue-guest-tokens: true` in the service registry
may call the endpoint — booking-service alone.

## 3.2 The cookie — separate, prefixed, and never in the account session's way

The guest token rides in its **own cookie: `__Host-skybook_guest`**
(httpOnly, Secure, SameSite=Lax, Path=/, no Domain — the `__Host-` prefix
makes those properties browser-enforced; browsers treat localhost as a
secure context, so dev works). Session-scoped, never persistent.

**Why not `skybook_session`:** reusing it would overwrite a signed-in
user's account session the moment they looked up a booking as a guest —
including the agency's own session, including every other tab. Review
finding, accepted in full. The two cookies coexist; neither writes over
the other.

**Gateway precedence — deterministic, by explicit path list.** The gateway
gains a `GUEST_CAPABLE_PATHS` list (the six guest endpoints, exact paths,
house doctrine). On those paths, `__Host-skybook_guest` is preferred when
present; everywhere else `skybook_session` is the only cookie consulted
and the guest cookie is ignored entirely. A signed-in user with both
cookies keeps their account everywhere except inside the guest check-in
surface — where the guest credential is the one that works. The
`Authorization` header still beats both (explicit beats ambient).

**Ending the session is explicit:** the guest page's "Done" calls
`DELETE /api/bookings/guest-session`, which expires the cookie — shared
computers should not depend on tab-closing. The frontend additionally
re-validates on `pageshow` with `persisted=true` (bfcache restore) and
bounces to the lookup form when the session is gone, so the back button on
a kiosk does not resurrect a dead session's screen from memory (§7).

## 3.3 Ownership, extended once — and failing as `404` outside scope

`AuthenticatedPrincipal` gains an optional `bookingId` (from the claim for
guest tokens, null otherwise). `SecurityAccess` gains:

```java
requireBookingAccess(String ownerSubject, Long bookingId)
  = privileged
  || subject.equals(ownerSubject)
  || (tokenType == GUEST && bookingId.equals(principal.bookingId()))
```

**Guest failures are `404`, not `403`.** For an account holder, "403 on
someone else's booking" reveals nothing they didn't know — ids are opaque.
For a guest, a `403`-vs-`404` split on *reference-derived* resources would
be an existence oracle for bookings. So when a guest token fails the
booking match, the guard throws not-found — outside a guest's scope, other
bookings do not exist. Review finding, accepted.

## 3.4 `accept-guest-tokens` — default deny across the fleet

New `JwtSecurityProperties` flag, exactly parallel to
`accept-service-tokens`, **default `false`**:

| Service | accept-guest-tokens | Why |
|---|---|---|
| api-gateway | `true` | the front door must pass them through |
| booking-service | `true` | guest-session issuance + booking view |
| checkin-service | `true` | check-in, passes, baggage, email |
| flight / inventory / payment / notification / auth | **`false` (default)** | no guest business; a guest token dies at the validator with the same "not accepted here" arm service tokens get |

The e2e suite asserts the rejection service by service (§9) — "guest works
where intended" and "guest is refused everywhere else" are both tested
claims, not configuration hopes.

# 4. What a Guest Can and Cannot Do (frozen matrix)

Access is **booking-level, deliberately** (decision D2): reference + one
active passenger's surname unlocks the whole booking including co-travellers'
check-in — this is how the industry's Manage-My-Booking works (a family
checks in together; the booker manages the group), and passenger-level
scoping would break the most common use while adding a matching step the
e-ticket doesn't support. The privacy consequence is stated and tested
(§9): any active passenger's surname opens the group booking; a
**cancelled** passenger's surname opens nothing.

| Action | Guest | Enforced by |
|---|---|---|
| Retrieve + view own booking (by id) | ✅ | guard clause (§3.3) + per-endpoint role allowance |
| View check-in state | ✅ | same |
| Check in (within window) | ✅ | same + `CheckInValidator` windows |
| Change seat post-check-in | ✅ owner parity | same rules, same seat inventory semantics as the owner flow — verified, not assumed (§2.6, §10 step 4) |
| Register baggage (manifest data) | ✅ | same + CHECKED_IN-only rule; no chargeable path (verified §10 step 4) |
| View/download boarding pass, QR | ✅ | same |
| Email boarding pass to chosen address | ✅ | checked-in only + counters (§5) |
| Cancel / modify / payments / invoices | ❌ 403 | the blanket-rule cages (§2.3) |
| Any other booking | ❌ **404** | `booking_id` mismatch (§3.3) |
| Any non-opted-in service | ❌ 401 | `accept-guest-tokens=false` (§3.4) |
| Admin/gate operations | ❌ 403 | existing per-endpoint ADMIN rules |

# 5. The Email Re-send

`POST /api/boarding-passes/checkin/{checkInId}/email`, body
`{"email": "..."}` (`@Email`-validated, trimmed), allowed for owner, admin,
and guest-of-this-booking.

- **Only after check-in** (`CHECKED_IN`/`BOARDED`), else 409.
- Re-emits the existing `BOARDING_PASS_GENERATED` event with
  `contactEmail` overridden — plus a **`resendId` (UUID) and the
  requesting subject** stamped into the event, so every delivery is
  attributable in logs and the consumer has an idempotency key to dedupe
  on. Full consumer-side dedupe (and exactly-once posture generally) is
  the transactional-outbox increment's job — the platform's email path is
  at-least-once today for *every* email, and this feature does not
  pretend otherwise; a rare duplicate boarding-pass email is annoying,
  not dangerous. Stated as inherited behavior with its fix already on the
  roadmap.
- **Counters are DB-backed, not in-memory** (review finding): a
  `boarding_pass_email_log` table (checkInId, resendId, requestedBy,
  address hash, sent_at) enforces max 3 sends per check-in per hour with
  a query, which is correct at any instance count and doubles as the
  audit trail + a Prometheus counter (`skybook_boarding_pass_resends`)
  for abuse dashboards.

# 6. Retrieval Hardening

- **Lookup pair**: `bookingReference` + `lastName`, matched against
  **active (non-cancelled)** passengers.
- **Surname normalization, defined precisely** (review finding): both sides
  are NFD-decomposed with combining marks stripped (é→e), uppercased with
  `Locale.ROOT`, and every non-letter removed (spaces, hyphens,
  apostrophes) before comparison — so `O'Brien`, `o brien`, `OBRIEN` and
  `Óbrien` all match the stored `O'Brien`, and `García-López` matches
  `Garcia Lopez`. One static method in booking-service with its own test
  table; the same rule the airline industry's ASCII-uppercase ticketing
  effectively applies.
- **One generic failure**: unknown reference, wrong name,
  cancelled-passenger name, fully cancelled booking → identical 404 body
  ("We couldn't find a booking matching those details"), computed through
  the same code path so timing doesn't split them.
- **Layered throttles** (review finding — per-IP alone dies to a botnet):
  1. Gateway per-source limiter (exists, unchanged).
  2. **Per-reference counter, DB-backed** in booking-service: max 5 failed
     attempts per booking reference per 15 minutes, across all sources and
     instances — a distributed guess against one booking locks that
     booking's lookup, not the endpoint.
  3. Issuance logged with the reference masked to two characters + subject
     + source, feeding the existing Loki/Grafana stack.
- Honest limitation, stated for the case study: reference + surname is
  weak-entropy authentication the whole industry accepts because the blast
  radius is check-in. This design keeps the bargain by construction —
  and narrows it (§3.4, §4, §3.3).

# 7. Frontend

- Public route **`/check-in`**, header-linked for signed-out and
  signed-in non-admin users.
- Two fields → guest session → **lean guest page** (own components +
  `BoardingPassCard` + `printable.ts`): flight summary, passenger list
  with check-in state, per-passenger pass cards, "Email my boarding pass",
  and **Done** (explicit session end, §3.2). Owner-only actions are not
  hidden here — they are absent.
- A signed-in user on `/check-in` keeps their account session everywhere
  else (§3.2 precedence); the page itself always drives the guest
  credential.
- 401 anywhere in the guest surface → back to the lookup form with "That
  session ended — enter your details again." `pageshow`/bfcache
  revalidation per §3.2.
- The signed-in trips experience is untouched.

# 8. Two Accepted Limitations, Stated

## 8.1 A stateless guest token cannot be revoked mid-life

Same property as every SkyBook token (no revocation list —
SECURITY_HARDENING_MODULE.md §14). Mitigations: 30-minute TTL, and §8.2.
The alternative — a server-side guest-session store — would introduce the
first stateful auth component in the platform for a marginal win on a
30-minute check-in credential. Declined, visibly.

## 8.2 The state machines are the safety net for post-issuance changes

Booking cancelled, or a passenger removed, *after* a guest token was
minted (review finding): the token still validates, but every action dies
in the existing state machines — cancelled check-in rows refuse
check-in/seat/baggage transitions, the pass endpoint has nothing to serve
for a voided check-in, and the CANCELLED event has already revoked passes
downstream. What remains is **read access to the cancelled booking's own
state for up to 30 minutes** — which is what an airline's "your booking
was cancelled" page shows anyway. Traced, tested (§9), accepted.

# 9. Testing Strategy

- **skybook-security**: guest coherence table; `accept-guest-tokens`
  default-false rejection arm; `requireBookingAccess` truth table
  (owner/admin/guest-of/guest-of-other→404/service).
- **booking-service**: issuance (match, each §6 mismatch → one generic
  404; cookie attributes incl. `__Host-` constraints; token claims), the
  surname-normalization table (O'Brien/García-López/case/spacing), the
  per-reference counter (6th failure inside 15 min → 429 even from a new
  source), the cage (guest → 403 on cancel/modify/list), guest 404 on a
  foreign booking id, `DELETE guest-session` expiry.
- **checkin-service**: extended guard truth table; the §2.8 skip removed;
  email endpoint (state rules, DB counter across simulated instances —
  two service contexts sharing one database, resendId in the emitted
  event); post-cancellation actions refused (§8.2).
- **gateway**: `GUEST_CAPABLE_PATHS` precedence (both cookies present →
  guest wins inside the list, session wins outside), inbound
  `X-Auth-User` stripped, `Cache-Control: no-store` family present on
  booking/pass responses.
- **e2e certification**: the full journey (agency books → guest retrieves
  → checks in → pass → email lands in Mailpit at the chosen address) plus
  the rejection sweep — the same guest token presented to flight-service,
  inventory-service and payment-service answers 401 at each; money
  endpoints answer 403; a co-traveller's surname unlocks the group
  booking; a cancelled passenger's surname unlocks nothing.
- **Frontend**: lookup flows, guest page rendering, Done-expiry, 401 →
  form reset, bfcache revalidation.

# 10. Build Order

1. **skybook-security**: `TokenType.GUEST`, coherence rule,
   `accept-guest-tokens` (default false), `AuthenticatedPrincipal.bookingId`,
   `requireBookingAccess` with the guest→404 arm + tests.
2. **auth-service**: `/api/auth/guest-token` (Basic chain),
   `may-issue-guest-tokens` registry flag, `generateGuestToken`
   (sub=`guest:<bookingId>`, 30 min) + tests.
3. **booking-service**: normalization method + table, guest-session
   issue/delete endpoints (+ `__Host-` cookie), per-reference DB counter,
   `ROLE_GUEST` allowance on `GET /api/bookings/{id}`, guards switched,
   `accept-guest-tokens=true` + tests.
4. **checkin-service**: cage rewrite (§2.3), guards switched + §2.8 fix,
   **parity verifications recorded in the PR** (seat-change surcharge
   behavior for owners; baggage has no chargeable path), email endpoint +
   `boarding_pass_email_log` + event `resendId` + tests,
   `accept-guest-tokens=true`.
5. **Gateway**: `PUBLIC_PATHS` + explicit route for
   `/api/bookings/guest-session` (both methods), `GUEST_CAPABLE_PATHS`
   precedence, inbound `X-Auth-User` strip + tests.
6. **Frontend**: `/check-in` route, header link, guest page, Done, email
   affordance, bfcache handling + tests.
7. **e2e journey + rejection sweep** + full suites green + Sonar gate.
8. **Live verification on LOCAL**: agency account books; guest retrieves,
   checks in, downloads, emails to a second inbox (Mailpit shows both
   addresses); cage 403s; cross-service 401s; then the pipeline walk.

# 11. Decision Log

| # | Decision | Recommendation & reasoning | Status |
|---|---|---|---|
| D1 | Lookup = reference + last name only | The industry pair; extra fields add friction, not security | Proposed |
| D2 | **Booking-level access** (any active passenger's surname unlocks the group booking) | Industry behavior; passenger-level scoping breaks family check-in for marginal privacy gain; consequence stated + tested | Proposed |
| D3 | Guest = first-class `TokenType`, `ROLE_GUEST`, `booking_id` claim, **sub carries the numeric id, never the PNR** | Strict validator demands it; subjects flow into logs and the PNR is half the credential | Proposed |
| D4 | Issuance: booking-service verifies + sets **`__Host-skybook_guest`**; auth-service mints (Basic chain); gateway precedence by explicit path list | Verification lives with the data; minting with the key holder; account sessions untouchable by guest flows | Proposed |
| D5 | Email = re-emit `BOARDING_PASS_GENERATED` with overridden address + `resendId`, post-check-in only, **DB-backed counter** | Reuses the QR+PDF pipeline; counter is instance-safe and doubles as audit trail; duplicates inherited until outbox | Proposed |
| D6 | Separate lean guest page; `BookingDetailPage` untouched | Absence of buttons beats hiding of buttons | Proposed |
| D7 | **`accept-guest-tokens`, default false, opt-in per service** | Guest reach is a per-service decision made in configuration, tested in e2e — not an accident of audience overlap | Proposed |
| D8 | **Guest scope failures answer 404** | Outside its one booking, nothing exists; closes the existence oracle | Proposed |

# 12. Deferred, Explicitly

- Agency portal / `ROLE_AGENT` with multi-booking visibility.
- SMS delivery · wallet passes.
- Passenger-level guest scoping (revisit if a privacy requirement arrives).
- Consumer-side email dedupe — lands with the transactional outbox.
- Guest access to IRROPS rebooking (waits for IRROPS).

# 13. Review Round 1 — Disposition of All 30 Findings

| Finding | Disposition |
|---|---|
| Guest tokens accepted too broadly | **Accepted → D7** `accept-guest-tokens` default-false (§3.4) |
| checkin `.authenticated()` exposes future endpoints | **Accepted** — cage rewrite (§2.3) |
| Any surname unlocks whole booking / booking-vs-passenger not chosen | **Decided explicitly** — booking-level, industry-standard, stated + tested (D2, §4) |
| Shared cookie overwrites signed-in session / tab bleed | **Accepted** — separate `__Host-skybook_guest` + gateway precedence list (§3.2) |
| PNR in JWT subject leaks to logs | **Accepted** — sub = `guest:<bookingId>` (§3.1) |
| PNR remains in URLs post-auth | **Accepted** — issuance returns `bookingId`; guest flow is id-based; PNR only ever in one POST body (§2.5) |
| 45-min token not revocable | **Accepted as stated limitation** — TTL→30 min + state-machine safety net (§8.1) |
| Cancellation/removal after issuance | **Traced + tested** — §8.2 |
| Per-IP limiting insufficient / no per-reference limit | **Accepted** — DB-backed per-reference counter (§6) |
| 403/404 existence oracle | **Accepted → D8** — guests get 404 (§3.3) |
| Email throttle not multi-instance / no idempotency-audit id | **Accepted** — `boarding_pass_email_log` table + `resendId` + requester in event (§5) |
| Kafka duplicate emails | **Inherited platform trait, stated** — at-least-once until the outbox increment; groundwork (`resendId`) laid (§5) |
| Email abuse monitoring | **Accepted** — audit table + Prometheus counter + Grafana (§5) |
| `__Host-` prefix / explicit deletion / session switch | **Accepted** — §3.2, incl. `DELETE guest-session` |
| `Cache-Control: no-store` / bfcache on shared computers | **Accepted** — Spring Security defaults pinned by test + `pageshow` revalidation (§2.9, §3.2, §9) |
| Surname normalization (hyphens/apostrophes/accents) | **Accepted** — precise NFD rule + test table (§6) |
| Guests choosing paid/restricted seats | **Owner-parity, verified in build step 4** — guests gain no privilege owners lack (§2.6); if owners can, that is a pre-existing platform question independent of guests |
| Exit-row / accessibility seat rules | **Out of scope, stated** — no such model exists platform-wide; would be its own feature |
| Concurrent seat selection | **Inherited** — same seat-change path and inventory semantics as owners; nothing guest-specific to add |
| Baggage chargeable changes | **Verified in build step 4** — registration is manifest data; purchases live in caged booking-modify |
| Basic credential storage/rotation/TLS | **Governed by the existing posture** (hardening §3.3/§10) — the new endpoint sits on the same chain with the same registry; nothing new invented |
| Spoofed identity headers | **Accepted** — gateway strips inbound `X-Auth-User` (§2.9); identity was never header-derived downstream |
| Guest tested across every service | **Accepted** — e2e rejection sweep per service (§9) |
| Multi-instance rate-limit testing | **Accepted** — DB-backed counters tested with two contexts on one database (§9) |
| Group bookings, differing surnames | **Tested** — co-traveller unlocks (by design, D2); cancelled passenger does not (§9) |
