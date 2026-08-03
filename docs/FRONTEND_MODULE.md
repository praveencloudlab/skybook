# 🖥️ SkyBook Frontend — Design

---

## Project Information

| | |
|---|---|
| **Scope** | A passenger-facing web client for the full customer journey — register/login → search → quote → seat selection → book → pay → check-in → boarding pass — talking to the API gateway and nothing else |
| **Branch** | `feature/frontend` |
| **Status** | ✅ **Implemented and verified** — all 15 build-order steps complete on `feature/frontend`. The full passenger journey works against the live platform: register/login → search → fares → seat map → passenger details → payment → confirmation → my bookings → check-in → boarding pass. **Note §10.1 was reversed during implementation**: the frozen `sessionStorage` decision was replaced with an httpOnly session cookie after review; see §10.1 and §12. |
| **Stack** | React + Vite + TypeScript, Tailwind CSS (approved) |
| **Depends on** | Everything merged: dockerization, ci-cd, observability, resilience, seat-selection, security-hardening, e2e-certification |

Goal: the platform is certified but invisible. This is the first module a human
can actually *look at* — and the first consumer of the API that is not a test.

---

# 1. Load-Bearing Findings (traced against the running platform, not assumed)

Every item here was observed while building the e2e suite against the live fleet.
Each one changes the frontend design, which is why they lead.

1. **The gateway is the only surface, and it already expects us.**
   `http://localhost:8080` is the sole host-published application port; CORS
   already allows `http://localhost:5173` (Vite's default) and
   `http://localhost:3000`. No backend change is needed to start.

2. **`POST /api/auth/login` returns a RAW JWT string — not JSON.** The body is the
   token itself. `response.json()` will throw. This has already bitten a Postman
   script and the e2e suite; it will bite the frontend too unless the API client
   special-cases it.

3. **Three steps of the journey complete *asynchronously*, over Kafka.** These are
   not slow HTTP calls — the resource genuinely does not exist yet when the
   previous call returns:
   - booking created → **payment row appears** (payment-service consumes the event)
   - payment captured → **booking becomes CONFIRMED**
   - booking confirmed → **check-in records appear**

   This is the single biggest UX constraint in the module: the UI *must* have a
   first-class "we're processing this" state and poll. Pretending these are
   synchronous will produce a UI that looks broken at exactly the moments the
   user cares most (right after paying).

4. **The gateway rate-limits at 100 req/min.** The e2e suite tripped it and got
   429s that looked like product failures. A naive `setInterval(fetch, 500)` will
   do the same to a real user. Polling must back off.

5. **Access tokens last 60 minutes and there is no refresh token.** Refresh/
   revocation are explicitly deferred (security module §14). So the client cannot
   silently renew: a 401 means "send them to login", and the UI must not lose the
   user's in-progress work when it happens.

6. **The passenger journey ends at the boarding pass.** Boarding is an ADMIN/gate
   operation — a passenger who could board themselves would defeat the gate, and
   the platform correctly returns 403. The UI must not offer a "board" button.

7. **Check-in is time-windowed and the window is enforced**: opens 24h before
   departure, closes 45 min before. Attempting it early returns
   `409 "Check-in for flight X does not open until ..."`. The UI should render
   *why* check-in is unavailable rather than showing a button that 409s.

8. **Seat selection has real pricing data to render.**
   `GET /api/aircraft/{aircraftId}/seat-map` returns every seat with
   `seatType`, `position` (WINDOW/MIDDLE/AISLE), `exitRow` and `listedSurcharge`;
   `GET /api/inventory/flight/{flightId}` gives the `aircraftId` and availability.
   Auto-assignment is free; a chosen seat adds its surcharge. That is enough for a
   proper seat map, not a dropdown.

9. **`CreateBookingRequest` still requires a `customerId`** even though ownership
   is derived from the token's subject. It is a legacy wart; the client has to
   send something. Flagged in §10.3 rather than silently hard-coding a value.

---

# 2. Architecture

```
browser ──HTTPS──► api-gateway :8080 ──► services (never addressed directly)
   │
   ├── src/api/        one typed client per service surface; the ONLY place fetch() appears
   ├── src/features/   auth · search · booking · payment · checkin  (vertical slices)
   ├── src/components/ shared presentational pieces (SeatMap, FlightCard, Money…)
   └── src/lib/        auth storage, polling, error mapping
```

**Vertical slices, not layer folders.** The journey is the product; a `features/booking`
that owns its screens, hooks and types is easier to reason about than
`components/` + `hooks/` + `pages/` split three ways.

**One API client boundary.** Every network call goes through `src/api`, so the
token header, 401 handling, error mapping and rate-limit-aware polling exist in
exactly one place rather than being reinvented per screen.

---

# 3. The Async Problem (the part that needs real design)

Three journey steps resolve over Kafka (§1.3). The naive approach — poll every
500 ms until it appears — is what the e2e suite did, and it tripped the gateway's
rate limiter.

**Proposal: a single `usePolledResource` hook** with:
- **backoff**: 1s → 2s → 4s, capped (well under the 100 req/min budget even with
  several tabs open),
- **a deadline** (~45s, matching the e2e suite's proven-generous bound), after
  which the UI stops and offers "check again" rather than spinning for ever,
- **an explicit UI state machine**: `idle | working | ready | timed-out | failed`.

"Timed out" deserves its own state and its own copy. After a successful payment,
"we're confirming your booking — this usually takes a few seconds" is honest and
calm; an infinite spinner is neither.

---

# 4. Auth & Session

- Login stores the raw token (see §1.2 — no JSON parsing).
- **A 401 from any call = session over.** One interceptor clears the token and
  routes to login with a `returnTo`, so an expiry mid-journey does not dump the
  user on a blank page.
- **Storage: `sessionStorage`, not `localStorage`** — proposed. The token is a
  60-minute bearer credential with no revocation; scoping it to the tab limits the
  blast radius of an XSS and means a shared machine does not leave a live session
  behind. Trade-off: a refreshed tab keeps the session, a new tab does not.
- Role is read from the token's `roles` claim only to hide UI a passenger cannot
  use. **This is cosmetic, not security** — the platform already enforces
  authorization server-side, and the UI must never be the thing standing between a
  user and an ADMIN action.

---

# 5. Screens (v1)

| # | Screen | Notes |
|---|---|---|
| 1 | Register / Login | Password policy surfaced *before* submit (12+, upper/lower/digit/symbol) — the API's 400 is accurate but late |
| 2 | Search | Date-range flight search; results as cards with route, times, airline |
| 3 | Flight + Quote | Cabins, seats remaining, fares per fare type ("Economy from ₹X") |
| 4 | Seat map | Real map from the seat map API: cabin, window/aisle, exit rows, surcharge per seat. "Skip — assign me one" is free and must be an obvious, unpenalised choice |
| 5 | Passenger details | Validation mirrored from the API contract (passport, DOB, nationality ISO-3) |
| 6 | Review + Pay | Fare breakdown incl. seat surcharge; authorize → capture; **declines are 422 and must read as "card declined", not "something went wrong"** |
| 7 | Confirmation | PNR front and centre; the async CONFIRMED wait lives here (§3) |
| 8 | My bookings | List + detail by id/PNR |
| 9 | Check-in | Enabled only inside the window, with the reason shown when it is not (§1.7) |
| 10 | Boarding pass | Seat, gate, boarding group, and the signed token rendered as a scannable code. **Journey ends here** (§1.6) |

---

# 6. Error Handling

The platform's error surface is already consistent — the client should not flatten
it back into "something went wrong":

| Status | Meaning here | UI |
|---|---|---|
| 400 | Validation | Field-level messages from the `ErrorResponse` |
| 401 | Session over | Redirect to login, preserve `returnTo` |
| 403 | Not yours / not your role | "You don't have access to this booking" — never a raw 403 |
| 409 | Conflict (seat taken, window closed, already checked in) | Actionable: "That seat was just taken — pick another" |
| 422 | Payment declined | "Your card was declined" + retry with another method |
| 502 | A service is down | "We can't reach our booking system right now" + retry; **do not** blame the user |

The 409-on-seat case is real and reachable: the platform genuinely resolves
concurrent seat grabs by rejecting the loser (certified in the e2e suite), so the
UI must handle losing a seat race gracefully.

---

# 7. Testing

- **Vitest + Testing Library** for component/hook logic — especially the polling
  hook and the error mapper, which is where the subtle bugs will live.
- **MSW** to fake the gateway, so screens are testable without the fleet running.
- **No new e2e layer.** `backend/e2e-tests` already certifies the platform; adding
  Playwright now would duplicate it. Revisit once the UI is stable (§9).

---

# 8. Build & Serve

- Vite dev server on **5173** (already in the gateway's CORS allow-list) — no
  backend change needed to start developing.
- Production: static build served by nginx, containerised like everything else,
  added to compose on **3000** (reserved for this since the observability module
  put Grafana on 3001).
- CI: lint + typecheck + unit tests. Kept as a **separate workflow** from the Java
  reactor — a frontend change should not trigger an 8-image backend matrix.

---

# 9. Explicitly Out of Scope (v1)

- **Admin/back-office UI** — the ADMIN surface is real and worth building, but it
  roughly doubles the module. Passenger journey first.
- **Playwright/browser e2e** — see §7.
- **i18n, PWA/offline, real payment SDK** — the gateway is simulated; a real one is
  a separate concern.
- **Refresh-token handling** — nothing to build against until the backend has it
  (security §14).

---

# 10. Decisions Settled

**10.1 — Browser authentication: an httpOnly cookie.** *(Revised. The original
decision was `sessionStorage`; see the ADR below for why it changed.)*

> **Decision.** Browser authentication uses an **httpOnly cookie containing a
> signed JWT**. Services remain stateless and validate RS256 tokens locally.
> Revocation, refresh-token rotation, opaque session identifiers and centralised
> session management are **intentionally deferred** to the planned security phase
> (SECURITY_HARDENING_MODULE.md §14). The **API gateway is the sole translation
> point between the browser authentication credential and downstream bearer
> authentication**, enabling future migration to an opaque session store without
> modifying individual services.

Why it changed: `sessionStorage` is readable by JavaScript, so any XSS — including
one in a transitive dependency — can exfiltrate the token and replay it elsewhere
for the remainder of its 60-minute life, with no revocation to stop it. Tab-scoping
limits *persistence*, not *readability*.

Being precise about what httpOnly buys, since it is easy to overstate: it does
**not** prevent XSS. Injected script can still call the API from the victim's
browser while the page is open. What it prevents is the credential being **read
and taken away**. Given the token cannot be revoked, that distinction is the
whole point.

Two things make this practical here:
- **Same-origin serving.** The SPA is served from the same origin as the API
  (Vite proxy in development, nginx in the container), so `SameSite=Lax` is a
  real CSRF control and CORS disappears. A cross-origin SPA would have forced
  `SameSite=None`, which *is* sent cross-site and would have needed separate CSRF
  tokens.
- **The body token stays.** Login returns the JWT in the response body *and* sets
  the cookie. API clients (Postman, the e2e suite, scripts) keep working
  unchanged — they are not the ones exposed to XSS, and breaking them would have
  bought no security.

Two endpoints exist *because* the cookie is httpOnly — JavaScript can neither
clear it nor read it:
- `POST /api/auth/logout` — expires the cookie. Public, so a user whose token has
  already lapsed can still sign out rather than being stuck with a stale cookie.
  It does not *revoke* the token (there is no revocation list yet); it guarantees
  the browser stops presenting it.
- `GET /api/auth/me` — returns the subject and roles the server already validated.
  Better than the browser decoding an unverified JWT anyway: it cannot drift from
  what the server believes.

**Considered and deferred: an opaque session id with a server-side store.** This
is the common production pattern and its real payoff is *revocation* — a stolen
session id is just as replayable as a stolen JWT, but a server-side session can be
deleted. Deferred for two reasons. First, it would create a **mixed authentication
model**: revocable browser sessions alongside non-revocable API bearer tokens, so
every later feature (logout-everywhere, admin session kill, audit, expiry) would
have to answer "browser, API, or both?". Second, it belongs with the work it is
part of — refresh tokens, rotation, revocation, logout-everywhere, session store,
device management are all *token-lifecycle* concerns and should land as one
coherent design rather than several partial migrations. The gateway abstraction
above is what keeps that door open: swapping "read the credential from a cookie"
for "resolve a session id against a store" is a change in one method, and nothing
downstream moves.

**10.2 — Visual direction: real-airline, dense and information-rich.** Compact
fare tables, a genuine seat map, real data density — closer to what BA or Emirates
actually ship than to a spacious marketing-style product UI. This is the harder
option to make look good, and it is chosen deliberately: to a reader who knows the
domain it reads as credible airline work rather than a generic CRUD app dressed up.

**10.3 — Fix `customerId` in the backend.** It becomes derived (like
`ownerSubject`) rather than a required client input, instead of the client baking
in a meaningless constant. **This touches booking-service, which is merged and
certified**, so it is sequenced as its own first step with a full e2e
re-certification — and that re-certification is now cheap, which is precisely the
payoff of having built the suite.

**10.4 — Search defaults to curated popular routes.** A handful of recognisable
routes (LHR→JFK, LHR→DXB, …) so a first-time visitor immediately sees something
sensible; full search stays available. Showing 10,950 undifferentiated flights, or
an empty screen until the user guesses a seeded route, both make a working
platform look broken.

---

# 11. Build Order

Each step ends with something demonstrable, in the project's usual style.

1. **Backend: derive `customerId`** (§10.3) — make it optional/derived in
   booking-service, then **re-run the e2e suite** to prove the certified journey
   still holds. Done first so the frontend is never written against a contract we
   already intend to change.
2. **Scaffold** — Vite + React + TS + Tailwind, `src/api | features | components |
   lib`, dev server on 5173 (already CORS-allowed), lint + typecheck.
3. **API client + auth** — the single `fetch` boundary, the raw-JWT login quirk
   (§1.2), `sessionStorage`, 401 → login preserving `returnTo`.
4. **`usePolledResource`** (§3) — backoff, deadline, explicit
   `idle|working|ready|timed-out|failed`. Built early: three screens depend on it
   and it is where the subtle bugs will live.
5. **Register / login** — password policy surfaced before submit.
6. **Search + curated routes** (§10.4) and flight cards.
7. **Quote + fare table** — the first dense, real-airline surface.
8. **Seat map** — cabin, window/aisle, exit rows, per-seat surcharge; "assign me
   one" free and obvious.
9. **Passenger details + review + pay** — authorize → capture; 422 reads as
   "card declined".
10. **Confirmation** — PNR, plus the async CONFIRMED wait.
11. **My bookings** — list and detail.
12. **Check-in + boarding pass** — window state explained when closed; the journey
    ends at the pass (§1.6).
13. **Error surface pass** (§6) — 400/401/403/409/422/502 each rendered honestly,
    including losing a seat race.
14. **Containerise + CI** — nginx image on port 3000, frontend-only workflow.
15. **Doc → Implemented + Implementation Notes.**

---

# 12. Implementation Notes

All 15 build-order steps landed on `feature/frontend`, each verified against the
running fleet rather than against mocks. Below is an honest account of what the
frozen design got wrong or under-specified, in the spirit of this project's
other module post-mortems.

**Verification.** `npm run lint`, `typecheck`, `test` (45) and `build` all pass;
the containerised app was exercised end to end on `:3000` (SPA routes, cookie
auth through nginx, live flight search); the full booking journey was driven
against the real platform — booking `SBVHSR` CREATED → payment row appearing over
Kafka → authorize/capture CAPTURED → booking CONFIRMED → seat `17B` auto-assigned
free → check-in → boarding pass `BP-2026-7PDLVK`.

**The big one: the design's own §10.1 decision was wrong, and was reversed.**
The frozen doc chose `sessionStorage`. That is JS-readable, so any XSS — including
one in a transitive dependency — could exfiltrate a 60-minute, *unrevocable*
token and replay it elsewhere. The decision was revisited mid-build and replaced
with an **httpOnly cookie**, which required backend work the design had not
anticipated (§10.1 records the full ADR). Two consequences only became obvious
once building it: with an httpOnly cookie the browser **cannot sign itself out**
(logout has to be a server endpoint) and **cannot read its own claims** (hence
`GET /api/auth/me`). Neither was in the design.

**"My bookings" was not buildable as specified.** Screen 8 assumed a list
endpoint; `GET /api/bookings` and `/search` are ADMIN-only, so a passenger got
403 and could only fetch a booking whose PNR they already knew. It needed a new
owner-scoped `GET /api/bookings/mine` in booking-service.

**Things only the live platform revealed:**

1. **`money()` hardcoded GBP.** The quote endpoint returns **USD** for the seeded
   fares, so `$85.00` rendered as `£85.00` — a booking screen stating a false
   price. Currency is now a parameter, taken from the server's response.
2. **Check-in availability must come from the server.** I derived "too early"
   from a hardcoded 24-hour window; the fleet, running the e2e overrides that
   widen it, accepted a check-in the UI had disabled. The window is server
   configuration, so the UI now gates on `CheckInStatus` (`NOT_OPEN`/`OPEN`/…)
   and uses the times only to *explain*. The same test showed my `CheckInStatus`
   type invented a `CLOSED` value the enum does not have.
3. **The 400 body is one joined string** (`passengers: …, flightId: …,
   contact.contactEmail: …`), unfit to show verbatim, and its **nested paths**
   never matched the form's field names — so those messages silently vanished
   until each was indexed under both the full path and its leaf.
4. **Seat maps belong to the aircraft, not the flight.** `status` there means
   "blocked/out of service"; per-flight occupancy comes from the reservations
   endpoint. Reading the former as availability would have drawn every taken seat
   as free.

**Deliberately not hidden:** seat **holds** are not exposed by any endpoint, so a
seat someone is mid-booking looks free and the booking call answers 409. The UI
loses that race gracefully rather than pretending it cannot happen — the platform
resolves it correctly, and the double-sell e2e case certifies that.

**Container gotchas** (both invisible until it actually ran): a `tmpfs` mounts
**root-owned**, so on a read-only rootfs non-root nginx had nowhere writable
(`mkdir /var/cache/nginx/client_temp failed`), fixed with `mode=1777`; and the
read-only rootfs stops nginx's entrypoint adding its IPv6 listener, so the
healthcheck's `localhost` resolved to `::1` and was refused while the host got
clean 200s — it now uses `127.0.0.1`.

**Known gaps, stated plainly:**

- **The cookie path is not covered by the e2e suite**, which authenticates with
  `Authorization: Bearer`. It is covered by unit tests and live curl checks, but
  the certification suite should gain a cookie case.
- **No CSP** is set on the nginx image. A real policy needs writing against the
  deployed asset set rather than guessing, and is the actual defence against the
  XSS that motivated the cookie change in the first place.
- **No browser-level e2e** (§7): `backend/e2e-tests` certifies the platform, and
  adding Playwright now would duplicate it.
- **Multi-passenger bookings** are modelled in the API but the checkout form
  collects one passenger; the request shape already carries a list.
