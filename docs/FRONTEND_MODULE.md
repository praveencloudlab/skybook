# 🖥️ SkyBook Frontend — Design

---

## Project Information

| | |
|---|---|
| **Scope** | A passenger-facing web client for the full customer journey — register/login → search → quote → seat selection → book → pay → check-in → boarding pass — talking to the API gateway and nothing else |
| **Branch** | `feature/frontend` |
| **Status** | 📝 **DRAFT — for review.** Not frozen. §10 lists what needs your call. |
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

# 10. Open Questions (need your call before freezing)

**10.1 — Token storage.** `sessionStorage` (my recommendation, §4) or
`localStorage` for a session that survives a browser restart? This is a real
security/convenience trade-off, not a detail.

**10.2 — Design direction.** Should this look like a *real* airline (dense,
information-heavy, closer to what BA/Emirates actually ship) or a clean modern
product UI? It changes the whole visual language, and for a portfolio the first
reads as more credible while the second reads as more designed.

**10.3 — The `customerId` wart (§1.9).** Options: (a) client sends a constant and
we accept the smell; (b) small backend change to derive it from the token like
`ownerSubject`; (c) leave it and document. (b) is the honest fix but touches a
merged, certified module.

**10.4 — Seed data in the demo.** The seed has 10,950 flights across 30 routes.
Should search default to a curated set of routes so a first-time visitor sees
something sensible, or show the raw breadth?
