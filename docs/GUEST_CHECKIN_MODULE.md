# 🎫 SkyBook Guest Check-in — Retrieve, Check In, Boarding Pass Without an Account — Design

## Project Information

| | |
|---|---|
| **Author** | Praveenreddy Somireddy |
| **Status** | PROPOSED — awaiting review; implementation starts on freeze |
| **Scenario** | A travel agency books on its own SkyBook account and sends the passenger the e-ticket. The passenger has no SkyBook login — and must still be able to retrieve the booking, check in online, get the boarding pass, and have it emailed to an address they choose. |
| **Depends on** | SECURITY_HARDENING_MODULE.md (RS256, gateway translation point, SecurityAccess ownership) · the check-in platform (CHECKIN_SERVICE_MODULE.md) |
| **Explicitly out of scope** | Cancelling, modifying, seats-for-purchase, invoices, payment data — money actions stay with the account that owns the booking (the agency), exactly as real airlines route them · an "agent" role or agency portal · SMS delivery |

# 1. Overview

One sentence governs the design:

> **A guest session is a session.** Retrieval by booking reference + last name
> mints a short-lived, booking-scoped token delivered in the same
> `skybook_session` cookie as every other sign-in — and from that moment the
> gateway, the services, and the frontend treat the guest like any caller,
> with one new clause in the ownership check and a default-deny cage around
> everything money-shaped.

This is the industry-standard "Manage My Booking" pattern: the booking
reference is the identifier, the last name is the proof, and the blast
radius of that deliberately weak pair is capped at check-in — which is why
every real airline accepts it.

# 2. Load-Bearing Findings (traced against the live code, not assumed)

## 2.1 The ownership machinery is two guards and one static method

Every check-in ownership decision funnels through
`CheckInAccessGuard.requireOwnerOfCheckIn/requireOwnerOfBooking`
(checkin-service) and `BookingAccessGuard.requireOwnerOfBookingByReference`
(booking-service), both delegating to
`SecurityAccess.requireOwnerOrAdmin(ownerSubject)` (skybook-security), which
compares the token subject to the entity's snapshotted `owner_subject`.
**Extending ownership means extending one method and passing one extra
argument at the guard call sites** — not touching business logic anywhere.

## 2.2 The shared validator is strict by design — guest must be a first-class type

`JwtTokenValidator` (skybook-security) rejects any `token_type` outside the
`TokenType` enum (`user`, `service`), and enforces **type↔role coherence**:
a USER token must carry exactly `{ROLE_USER}` or exactly `{ROLE_ADMIN}`; a
SERVICE token exactly `{ROLE_SERVICE}`. There is no loophole to smuggle a
guest through — and that is a feature. `GUEST` becomes the third enum value
with its own coherence rule (exactly `{ROLE_GUEST}`) and the user audience,
so the change is visible in one place and every service picks it up from the
shared library. The gateway's `accept-service-tokens=false` is untouched —
a guest token is not a service token.

## 2.3 booking-service's blanket rule is the guest's cage, for free

booking-service ends its chain with `.anyRequest().hasAnyRole("USER",
"ADMIN")`. A `ROLE_GUEST` token therefore gets **403 on every booking
endpoint by default** — cancel, modify, payments view, everything — and
guest access is opened per endpoint, by name, exactly like the gateway's
explicit-path doctrine. The cage exists before the first line of this
feature is written. (checkin-service ends with `.anyRequest()
.authenticated()`, where the per-endpoint ADMIN rules already fence gate
operations; guests are admitted by the chain and constrained by the
extended ownership guard.)

## 2.4 The boarding-pass email already exists — this feature re-aims it

notification-service's `CheckInEventConsumer` already turns a
`BOARDING_PASS_GENERATED` event into an HTML email with an inline QR **and a
PDF boarding pass attached**, sent to the event's `contactEmail`. In the
agency scenario that address is the *agency's*. The guest feature needs one
new thing only: a re-send endpoint that emits the same event with the
passenger's chosen address — the consumer, templates, QR and PDF pipeline
are reused byte for byte.

## 2.5 The retrieval endpoint exists; only its guard needs the new clause

`GET /api/bookings/reference/{pnr}` already fetches by reference and guards
with `requireOwnerOfBookingByReference`. The guest path reuses it as-is.

## 2.6 Check-in windows, seat changes, baggage are all server-enforced

`CheckInValidator` enforces open/close windows (24 h → 45 min before
departure, on the departure airport's clock), seat-change state rules, and
baggage state rules inside the service. Nothing about guest access weakens
any of it — a guest hits the same validators through the same endpoints.

## 2.7 The check-in UI is embedded in an authenticated page

The passenger check-in flow lives inside `BookingDetailPage` (route under
`RequireSession`), with `BoardingPassCard` and the printable-pass helper as
standalone pieces. The guest page reuses the standalone pieces; the deep
page stays where it is (§7).

## 2.8 One pre-existing wart, fixed in passing

`CheckInAccessGuard.requireOwnerOfBooking` **skips the ownership check when
the booking has no check-in rows** (an empty list passes through). Harmless
today — the follow-up query returns the same empty list — but it is a
latent trap for any future code that branches on "guard passed". The guard
gains the booking-match clause and loses the skip.

# 3. The Flow

```
Browser                    Gateway                booking-service            auth-service
  |  POST /api/bookings/guest-session {bookingReference, lastName}             |
  |─────────────────────────►|──────────────────────►|                         |
  |                          |     verify: booking exists AND an active,       |
  |                          |     non-cancelled passenger's last name         |
  |                          |     matches (normalized, case-insensitive)      |
  |                          |                       |  POST /api/auth/guest-token
  |                          |                       |  (HTTP Basic, its own   |
  |                          |                       |   service credential)   |
  |                          |                       |──────────────────────►  |
  |                          |                       |   RS256 guest token ◄───|
  |◄─────────────────────────|◄──────────────────────|  204 + Set-Cookie:      |
  |                          |                       |  skybook_session=<guest>|
  |  ...from here, plain existing traffic with the cookie:                     |
  |  GET /api/bookings/reference/{pnr}     → the booking (view)                |
  |  GET /api/checkins/booking/{id}        → check-in rows                     |
  |  PATCH /api/checkins/{id}/checkin      → check in (window rules apply)     |
  |  PATCH /api/checkins/{id}/seat         → free seat change                  |
  |  GET /api/boarding-passes/checkin/{id} → the pass (QR on screen, download) |
  |  POST /api/boarding-passes/checkin/{id}/email {email} → PDF to that inbox  |
```

## 3.1 The guest token

Minted by auth-service — the fleet's only minter — via a new
`POST /api/auth/guest-token` endpoint on the **existing client-credential
chain** (HTTP Basic, same chain as `/api/auth/service-token`, equally
unrouted from the public edge):

| Claim | Value | Why |
|---|---|---|
| `sub` | `guest:<bookingReference>` | Human-readable in logs; can never collide with an email subject |
| `token_type` | `guest` | Third `TokenType`; coherence rule: exactly `{ROLE_GUEST}` |
| `roles` | `["ROLE_GUEST"]` | Caged by booking-service's blanket rule (§2.3) |
| `aud` | the user audience (`skybook-api`) | The gateway and services already accept it |
| `booking_id` | the booking's numeric id | The scope: the ONE booking this session may touch |
| `exp` | 45 minutes | A check-in errand, not a session; no refresh |

Only clients flagged `may-issue-guest-tokens: true` in the service registry
may call the endpoint — booking-service alone. Least privilege holds: a
checkin-service credential cannot mint browser-facing tokens.

## 3.2 The cookie

The token rides in the **same `skybook_session` cookie** (httpOnly, Secure,
SameSite=Lax, session-scoped — never persistent for a guest), set by
booking-service on the guest-session response. The gateway's cookie→Bearer
translation then works unchanged, which is the entire point: no second
cookie name, no second translation path, no frontend token handling.

## 3.3 Ownership, extended once

`AuthenticatedPrincipal` gains an optional `bookingId` (populated from the
claim for guest tokens, null otherwise). `SecurityAccess` gains:

```java
requireBookingAccess(String ownerSubject, Long bookingId)
  = privileged
  || subject.equals(ownerSubject)
  || (tokenType == GUEST && bookingId.equals(principal.bookingId()))
```

The three guard methods pass the entity's booking id and switch to it.
A guest token for booking 41 presented against booking 77 fails exactly as
a stranger's account does — same 403, no distinction leaked.

# 4. What a Guest Can and Cannot Do (frozen matrix)

| Action | Guest | Enforced by |
|---|---|---|
| Retrieve + view own booking | ✅ | guard clause (§3.3) + per-endpoint role allowance |
| View check-in state | ✅ | same |
| Check in (within window) | ✅ | same + `CheckInValidator` windows |
| Change seat post-check-in (free) | ✅ | same + seat-change state rules |
| Register baggage | ✅ | same + baggage state rules (bags were paid at booking; this is manifest data, not money) |
| View/download boarding pass, QR | ✅ | same |
| Email boarding pass to chosen address | ✅ new endpoint | checked-in only + rate limit |
| Cancel booking / passengers | ❌ 403 | blanket `hasAnyRole(USER,ADMIN)` — the cage |
| Modify / rebook | ❌ 403 | same |
| See payments, invoices, refunds | ❌ 403 | same |
| Any other booking | ❌ 403/404 | `booking_id` claim mismatch |
| Admin/gate operations | ❌ 403 | existing per-endpoint ADMIN rules |

# 5. The Email Re-send

`POST /api/boarding-passes/checkin/{checkInId}/email` with body
`{"email": "..."}` (validated `@Email`), allowed for owner, admin, **and
guest-of-this-booking** alike — an account holder wanting the pass at a
second address is the same feature.

- **Only after check-in**: state must be `CHECKED_IN`/`BOARDED`, else 409 —
  there is no pass to send, and an "email anything anywhere" endpoint is a
  spam cannon otherwise.
- Mechanism: checkin-service re-emits the **existing**
  `BOARDING_PASS_GENERATED` `CheckInEvent`, `contactEmail` overridden to the
  supplied address. The consumer, QR, HTML template and PDF attachment are
  reused untouched (§2.4).
- Throttled: the gateway's per-source limiter covers volume; the endpoint
  additionally refuses more than 3 sends per check-in per hour (in-service
  counter) so a single guest session cannot carpet-bomb either.

# 6. Retrieval Hardening

- **The lookup pair is `bookingReference` + `lastName`** — the industry
  pair. The name must match an **active (non-cancelled)** passenger,
  compared trimmed and case-insensitively.
- **One generic failure**: "We couldn't find a booking matching those
  details." Wrong reference, wrong name, cancelled-out passenger, even a
  fully cancelled booking — identical response, identical timing shape (the
  lookup runs both checks before answering). No enumeration.
- The gateway rate limiter (per-source) applies as everywhere; the endpoint
  logs issuance with the reference masked to its first two characters.
- Honest limitation, stated for the case study: PNR + last name is
  weak-entropy authentication that the whole industry accepts because the
  blast radius is check-in, not money. This design keeps that bargain by
  construction (§2.3, §4).

# 7. Frontend

- **Public route `/check-in`**, linked from the header for signed-out AND
  signed-in non-admin users ("Check-in") — real airlines surface it
  top-level; an account holder mid-airport with a dead session benefits the
  same way.
- The page: two fields (booking reference, last name) → guest session → a
  **lean guest view**: flight summary, passenger list with check-in state,
  the existing `BoardingPassCard` per checked-in passenger, the existing
  printable download, plus the "Email my boarding pass" affordance. Built
  as its own page reusing the standalone pieces (`BoardingPassCard`,
  `printable.ts`, the check-in API client) — `BookingDetailPage` is not
  bent around a second identity mode; its owner-only buttons never render
  here because the page simply doesn't include them.
- Guest cookie expiry mid-flow → any 401 returns the guest page to the
  lookup form with "That session ended — enter your details again."
- The signed-in trips experience is untouched.

# 8. Failure Paths (frozen)

| Case | Response | Guest sees |
|---|---|---|
| Unknown reference / name mismatch / cancelled | 404, generic body | "We couldn't find a booking matching those details." |
| Guest token on a money endpoint | 403 (blanket rule) | never reachable from the guest UI |
| Guest token, different booking | 403 | same as any stranger |
| Expired guest cookie | 401 | back to lookup form with a sentence |
| Check-in window not open / closed | existing 409 + message | the window copy the owner flow already shows |
| Email before check-in | 409 | "Check in first — then we can send your boarding pass." |
| Email throttle exceeded | 429 | "That pass was emailed recently — try again in a little while." |
| Auth-service down at issuance | 502 via existing error shape | "Try again in a moment." |

# 9. Testing Strategy

- **skybook-security**: validator accepts a well-formed guest token; rejects
  guest+wrong-roles, guest+missing `booking_id` handled as null scope (fails
  every booking match); coherence table extended.
- **booking-service**: guest-session issuance (match / no-match / cancelled
  passenger / cancelled booking → one generic 404; cookie attributes;
  token requested with the right claims), the cage (guest → 403 on cancel/
  modify/list), retrieval with guest of right/wrong booking.
- **checkin-service**: extended guard truth table (owner / admin / guest-of /
  guest-of-other / service), the email endpoint (state rules, throttle,
  event emitted with overridden address), the §2.8 skip removed.
- **e2e certification**: one new journey — book as account A (the agency),
  guest-retrieve as the passenger, check in, fetch the pass, email it, and
  assert the money endpoints answer 403 to the guest session.
- **Frontend**: lookup form flows, guest page renders passes, 401 → form
  reset, email affordance states.

# 10. Build Order

1. **skybook-security**: `TokenType.GUEST`, coherence rule, audience
   mapping, `AuthenticatedPrincipal.bookingId`,
   `SecurityAccess.requireBookingAccess` + tests. (Shared library first —
   everything else compiles against it.)
2. **auth-service**: `/api/auth/guest-token` on the Basic chain,
   `may-issue-guest-tokens` registry flag, `JwtService.generateGuestToken`
   + tests.
3. **booking-service**: guest-session endpoint (verify → fetch token → set
   cookie), `permitAll` for it, `ROLE_GUEST` allowance on
   `GET /reference/{pnr}`, guard switched to `requireBookingAccess` + tests.
4. **checkin-service**: guards switched (and the §2.8 skip removed), email
   re-send endpoint + throttle + event emission + tests.
5. **Gateway**: `PUBLIC_PATHS` + explicit route addition for
   `/api/bookings/guest-session` (the `/api/bookings/**` route already
   forwards it; the public list is the change).
6. **Frontend**: `/check-in` route, header link, guest page + email
   affordance + tests.
7. **e2e journey** + full suites green + Sonar gate.
8. **Live verification on LOCAL**: agency account books; guest retrieves,
   checks in, downloads, emails to a second inbox (Mailpit shows both); the
   cage answers 403. Then the pipeline walk to prod.

# 11. Decision Log

| # | Decision | Recommendation & reasoning | Status |
|---|---|---|---|
| D1 | Lookup = reference + last name only (no from/to) | The industry pair; extra fields add friction, not security — both are printed on the e-ticket beside the reference | Proposed |
| D2 | Guest scope = view + check-in + seat + baggage + pass + email; **no money actions** | Matches reality (agency bookings change through the agency) and the weak-credential bargain; enforced by default-deny, not by UI absence | Proposed |
| D3 | Guest is a first-class `TokenType` with `ROLE_GUEST` and a `booking_id` claim | The strict validator (§2.2) makes anything else a hack; one shared-library change, every service inherits it | Proposed |
| D4 | Issuance: booking-service verifies, auth-service mints (Basic chain), cookie is the same `skybook_session` | Verification lives with the data; minting stays with the only private-key holder; the gateway translation path is reused unchanged | Proposed |
| D5 | Email = re-emit the existing `BOARDING_PASS_GENERATED` event with an overridden address, post-check-in only, throttled | Reuses the whole QR+PDF pipeline; the throttle turns a spam cannon into a feature | Proposed |
| D6 | Separate lean guest page; `BookingDetailPage` untouched | A second identity mode inside a deep owner page is where leaks are born; absence of buttons beats hiding of buttons | Proposed |

# 12. Deferred, Explicitly

- Agency portal / `ROLE_AGENT` with multi-booking visibility.
- SMS boarding pass delivery.
- Guest access to IRROPS rebooking (waits for the IRROPS feature itself).
- Apple/Google Wallet passes (the pass is HTML + PDF today).
