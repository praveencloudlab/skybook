# API Documentation

This page is the endpoint reference for the SkyBook platform: every HTTP endpoint exposed by the backend services, the authorization each one requires, and the shared error and authentication contracts. It is written for engineers integrating with or extending the APIs, and for reviewers checking that a change respects the existing security surface. All facts are taken from the controllers and security configuration in the repository (`backend/*/src/main/java/**`); the gateway routing and public-path list come from `GatewayRoutesConfig.java` and `JwtAuthenticationFilter.java` in `backend/api-gateway`. For how these services are built and promoted, see `04-cicd-release-process.md`.

## How requests enter the platform

All client traffic enters through the API gateway (Spring Cloud Gateway MVC, port `8080`; management endpoints on internal port `9080`). The gateway is a pure pass-through proxy — no path rewriting — with a static route table:

| Route prefix | Downstream service |
|---|---|
| `/api/auth/*` (explicit paths only, never a wildcard) | auth-service |
| `/api/profile/**` | auth-service |
| `/api/flights/**`, `/api/flight-schedules/**` | flight-service |
| `/api/bookings/**` | booking-service |
| `/api/inventory/**`, `/api/reservations/**`, `/api/aircraft/**` | inventory-service |
| `/api/payments/**`, `/api/refunds/**`, `/api/invoices/**` | payment-service |
| `/api/checkins/**`, `/api/boarding-passes/**`, `/api/baggage/**`, `/api/manifests/**` | checkin-service |

The auth route lists its endpoints explicitly so that `/api/auth/service-token` and `/api/auth/guest-token` (the machine-to-machine token endpoints) are never reachable from the public edge. notification-service exposes no HTTP API — it is driven entirely by Kafka events.

### Gateway-public paths

The gateway's JWT filter lets the following paths through without a token (`PUBLIC_PATHS` in the gateway's `JwtAuthenticationFilter`). Note the check is path-based: a write to a public path pattern (e.g. `DELETE /api/flights/1`) passes the gateway tokenless and is then rejected by the service's own role rules.

| Public path | Why |
|---|---|
| `/api/auth/register`, `/api/auth/login` | Account creation and sign-in |
| `/api/auth/logout` | Must work with an expired token (only expires the cookie) |
| `/api/auth/verify-email`, `/api/auth/resend-verification` | Pre-authentication: caller cannot sign in until verified |
| `/api/auth/forgot-password`, `/api/auth/reset-password` | Pre-authentication: caller cannot sign in |
| `/api/auth/oauth2/authorization/google`, `/api/auth/oauth2/callback/google`, `/api/auth/sso/providers` | Sign in with Google (start/callback legs + provider discovery) |
| `/api/flights/**` | Public shopping data (search, itineraries, calendar); writes still require ADMIN downstream |
| `/api/bookings/quote`, `/api/bookings/fare-calendar` | Public fare shopping data |
| `/api/bookings/guest-session` | Guest check-in front door (issue/end a guest session) |
| `/actuator/**`, `/livez`, `/readyz` | Health probes |

## Authentication mechanics

- **Two credential forms, one translation point.** The gateway accepts either an `Authorization: Bearer <JWT>` header (API clients, scripts, the e2e suite) or the httpOnly session cookie `skybook_session` (browsers). The header wins when both are present. `POST /api/auth/login` returns the token in the response body **and** sets the cookie; the `?remember=true` query parameter makes the cookie persistent (default is a session cookie).
- **Cookie-to-Bearer translation.** Because the cookie is httpOnly, JavaScript never sees the token. The gateway validates the cookie's JWT and injects an `Authorization: Bearer` header before forwarding, so downstream services only ever see Bearer auth. `GET /api/auth/me` exists because the SPA cannot decode the cookie itself; `POST /api/auth/logout` exists because JavaScript cannot delete it.
- **Defense in depth.** Tokens are RS256-signed. Every downstream service re-validates the token locally with the shared `JwtTokenValidator` rather than trusting the gateway. The gateway attaches the validated subject as `X-Auth-User` (logging/tracing only, never identity) and strips any inbound copy of that header.
- **Service tokens never cross the edge.** The gateway's validator is configured with `accept-service-tokens=false`, so `ROLE_SERVICE` tokens are only usable on the internal network. Services obtain them from `POST /api/auth/service-token` (HTTP Basic client credentials against the service-client registry), which is deliberately not routed by the gateway.
- **Guest sessions.** `POST /api/bookings/guest-session` (booking reference + surname) sets a booking-scoped, 30-minute httpOnly cookie `__Host-skybook_guest` and returns the `bookingId`. On the enumerated guest-capable paths (booking read by id/reference; check-in state, check-in, seat change; boarding-pass fetch/email; baggage add/list), the gateway uses the guest cookie instead of an account session only when the caller also sends `X-Skybook-Guest: 1` — the guest check-in page sends it on every call, so a signed-in user is never silently downgraded elsewhere.
- **Idempotency.** `POST /api/bookings` and `POST /api/payments` accept an optional `Idempotency-Key` header; a retried request with the same key replays the original result instead of creating a duplicate (payments replay with `200` instead of `201`).

### Auth column legend

| Value | Meaning |
|---|---|
| Public | No token required (gateway `PUBLIC_PATHS` + `permitAll` in the service) |
| USER/ADMIN | Any authenticated end-user token |
| ADMIN | `ROLE_ADMIN` required |
| Owner | Authenticated, plus an object-level ownership check in the controller (owner or ADMIN) |
| Owner/Guest | Owner-scoped, and additionally open to a booking-scoped `ROLE_GUEST` session for that booking |
| Service | Internal-network only: HTTP Basic client credentials or a `ROLE_SERVICE` token; unreachable through the gateway |

## auth-service

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/auth/register` | Public | Create an account (email verification required before sign-in) |
| POST | `/api/auth/verify-email` | Public | Redeem the emailed 6-digit code; `204` on success, generic `400` otherwise (no enumeration) |
| POST | `/api/auth/resend-verification` | Public | Mail a fresh verification code; always `202` (no enumeration) |
| POST | `/api/auth/login` | Public | Sign in; JWT in body + httpOnly `skybook_session` cookie; `?remember=` controls cookie persistence |
| POST | `/api/auth/logout` | Public | Expire the session cookie (no token revocation) |
| POST | `/api/auth/forgot-password` | Public | Start a password reset; always `202` (no enumeration) |
| POST | `/api/auth/reset-password` | Public | Redeem the emailed reset token and set a new password |
| GET | `/api/auth/me` | USER/ADMIN | Subject + roles of the presented token (the SPA's "who am I") |
| GET | `/api/auth/sso/providers` | Public | Which SSO providers are enabled in this environment (runtime discovery) |
| GET | `/api/auth/oauth2/authorization/google` | Public | SSO start leg (redirect to Google; redirects to sign-in with an error when SSO is disabled) |
| GET | `/api/auth/oauth2/callback/google` | Public | SSO callback leg |
| GET | `/api/auth/profile` | USER/ADMIN (not gateway-routed) | Authentication smoke-test endpoint |
| POST | `/api/auth/service-token` | Service | Mint a short-lived `ROLE_SERVICE` token for an allowlisted audience (HTTP Basic client credentials; internal only) |
| POST | `/api/auth/guest-token` | Service | Mint a booking-scoped guest token (requires the `may_issue_guest_tokens` grant; internal only) |
| GET | `/api/profile` | Owner | The caller's profile (subject resolved from the token, never from the request) |
| PUT | `/api/profile` | Owner | Update the caller's profile |
| POST | `/api/profile/change-password` | Owner | Change the caller's password |
| GET | `/api/profile/travellers` | Owner | List saved travellers |
| POST | `/api/profile/travellers` | Owner | Add a saved traveller (`201`) |
| PUT | `/api/profile/travellers/{id}` | Owner | Update a saved traveller |
| DELETE | `/api/profile/travellers/{id}` | Owner | Delete a saved traveller |

## flight-service

Reads of `/api/flights/**` are public shopping data (tokenless end to end); every write is ADMIN. Flight-schedule reads require any authenticated caller; schedule writes are ADMIN.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/flights` | ADMIN | Create a flight |
| POST | `/api/flights/bulk` | ADMIN | Create multiple flights in one request |
| GET | `/api/flights` | Public | Paged flight list (`page`/`size`, size capped at 200) |
| GET | `/api/flights/{id}` | Public | Flight by id |
| GET | `/api/flights/search` | Public | Search by origin, destination, departure date |
| GET | `/api/flights/itineraries` | Public | Direct + 1-stop + 2-stop connections with layover times |
| GET | `/api/flights/calendar` | Public | Bookable-departure counts per day for a route (date-picker feed) |
| GET | `/api/flights/status/{status}` | Public | Flights by status |
| GET | `/api/flights/departure-date` | Public | Flights departing on a date |
| GET | `/api/flights/departure-date-range` | Public | Flights departing between two dates |
| PUT | `/api/flights/{id}` | ADMIN | Update flight details |
| PATCH | `/api/flights/{id}/status` | ADMIN | Set flight status |
| PATCH | `/api/flights/{id}/delay` | ADMIN | Record a delay (new departure/arrival times) |
| PATCH | `/api/flights/{id}/reschedule` | ADMIN | Reschedule a flight |
| PATCH | `/api/flights/{id}/cancel` | ADMIN | Cancel a flight |
| PATCH | `/api/flights/{id}/board` | ADMIN | Mark boarding |
| PATCH | `/api/flights/{id}/depart` | ADMIN | Mark departed |
| PATCH | `/api/flights/{id}/arrive` | ADMIN | Mark arrived |
| DELETE | `/api/flights/{id}` | ADMIN | Delete a flight |
| POST | `/api/flight-schedules` | ADMIN | Create a recurring schedule template |
| GET | `/api/flight-schedules` | USER/ADMIN | List all schedules |
| GET | `/api/flight-schedules/{id}` | USER/ADMIN | Schedule by id |
| PATCH | `/api/flight-schedules/{id}/pause` | ADMIN | Pause generation (optional reason/remarks) |
| PATCH | `/api/flight-schedules/{id}/resume` | ADMIN | Resume a paused schedule |
| PATCH | `/api/flight-schedules/{id}/cancel` | ADMIN | Cancel schedule + its not-yet-departed flights |
| PATCH | `/api/flight-schedules/{id}/extend` | ADMIN | Extend the schedule's validTo date |
| POST | `/api/flight-schedules/{id}/generate` | ADMIN | Manually generate flight instances |

## booking-service

Owner scope is enforced in the controller via `BookingAccessGuard`. The two guest-readable shapes (by id, by reference) also accept a `ROLE_GUEST` session for that booking.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/bookings` | USER/ADMIN | Create a booking: validate flight, hold seats, generate PNR (optional `Idempotency-Key`) |
| POST | `/api/bookings/quote` | Public | Fare options for one flight: cabins, seats left, base fare per fare type |
| GET | `/api/bookings/fare-calendar` | Public | Per-date lowest fares for a route + cabin over a capped range |
| POST | `/api/bookings/fare-alerts` | USER/ADMIN | Watch a fare (route + date + cabin, repriced hourly, owner emailed on movement) |
| GET | `/api/bookings/fare-alerts` | USER/ADMIN | The caller's fare watches |
| DELETE | `/api/bookings/fare-alerts/{id}` | USER/ADMIN | Stop watching a fare |
| GET | `/api/bookings/mine` | USER/ADMIN | The caller's bookings, newest first (subject from token — nothing to tamper with) |
| GET | `/api/bookings/{id}` | Owner/Guest | Booking by id |
| GET | `/api/bookings/reference/{pnr}` | Owner/Guest | Booking by PNR |
| GET | `/api/bookings` | ADMIN | List all bookings (back office) |
| GET | `/api/bookings/search` | ADMIN | Multi-filter search (PNR, flight, passenger, passport, status, dates, contact) |
| PATCH | `/api/bookings/{id}/confirm` | ADMIN | Confirm the booking (publishes booking-confirmed event) |
| GET | `/api/bookings/{id}/cancellation-preview` | Owner | Live cancellation quote: refund tier, deadlines, per-passenger refunds |
| PATCH | `/api/bookings/{id}/cancel` | Owner | Cancel the booking, close check-in, refund if captured |
| POST | `/api/bookings/{id}/passengers/cancel` | Owner | Cancel selected passengers (booking becomes PARTIALLY_CANCELLED) |
| POST | `/api/bookings/{id}/passengers/{bookingPassengerId}/seat` | Owner | Pre-check-in seat change under the fare family's entitlement ceiling |
| POST | `/api/bookings/{id}/segments/{segmentIndex}/cancel` | Owner | Cancel one segment of a round trip ("drop the return") |
| POST | `/api/bookings/{id}/segments/{segmentIndex}/rebook` | Owner | Premium date change: move one segment to a new flight, same PNR/tickets |
| PATCH | `/api/bookings/{id}/complete` | ADMIN | Mark COMPLETED after the flight has flown |
| PATCH | `/api/bookings/{id}/passengers/{passengerId}/check-in` | Owner | Check in one passenger on the booking |
| PATCH | `/api/bookings/{id}/passengers/{passengerId}/board` | Owner | Board one passenger |
| POST | `/api/bookings/guest-session` | Public | Guest check-in front door: PNR + surname in, 30-min booking-scoped cookie out |
| DELETE | `/api/bookings/guest-session` | Public | End a guest session (expire the cookie, works even with a lapsed token) |

## inventory-service

The seat-operation surface is the internal service-to-service API (booking-service calls it with a service token); it is unreachable with a service token through the gateway. `GET .../cabins` is `permitAll` in-service so the public fare quote can fan out to it internally, but the gateway still requires a token for it from outside.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/inventory` | ADMIN | Create flight inventory |
| GET | `/api/inventory/flight/{flightId}` | USER/ADMIN | Inventory for a flight |
| POST | `/api/inventory/search` | USER/ADMIN | Search inventory by criteria |
| GET | `/api/inventory/flight/{flightId}/history` | USER/ADMIN | Inventory change history |
| GET | `/api/inventory/flights/{flightId}/cabins` | USER/ADMIN at edge (tokenless in-service) | Cabin availability feeding the public fare quote |
| POST | `/api/inventory/hold` | ADMIN or Service | Hold a chosen seat |
| POST | `/api/inventory/flights/{flightId}/holds/auto` | ADMIN or Service | Auto-assign and hold a low-demand seat in the cabin |
| POST | `/api/inventory/release` | ADMIN or Service | Release a seat hold |
| PATCH | `/api/inventory/flight/{flightId}/close` | ADMIN | Close inventory for sale |
| PATCH | `/api/inventory/flight/{flightId}/reopen` | ADMIN | Reopen inventory |
| POST | `/api/reservations` | ADMIN or Service | Convert a hold into a reservation |
| POST | `/api/reservations/cancel` | ADMIN or Service | Cancel a reservation |
| GET | `/api/reservations/booking/{bookingId}` | USER/ADMIN | Reservations for a booking |
| GET | `/api/reservations/flight/{flightId}` | USER/ADMIN | Reservations for a flight |
| POST | `/api/aircraft` | ADMIN | Register an aircraft |
| GET | `/api/aircraft` | USER/ADMIN | List aircraft |
| GET | `/api/aircraft/{id}` | USER/ADMIN | Aircraft by id |
| GET | `/api/aircraft/registration/{registrationNumber}` | USER/ADMIN | Aircraft by registration |
| GET | `/api/aircraft/status/{status}` | USER/ADMIN | Aircraft by status |
| PATCH | `/api/aircraft/{id}/status` | ADMIN | Update aircraft status |
| POST | `/api/aircraft/{aircraftId}/seats` | ADMIN | Add one seat |
| POST | `/api/aircraft/{aircraftId}/seat-map` | ADMIN | Create the full seat map |
| GET | `/api/aircraft/{aircraftId}/seat-map` | USER/ADMIN | Seat map for an aircraft |
| GET | `/api/aircraft/{aircraftId}/seats/status/{status}` | USER/ADMIN | Seats by status |
| PATCH | `/api/aircraft/{aircraftId}/seats/{seatNumber}/status` | ADMIN | Update one seat's status |

## payment-service

Owner scope is enforced in the controller via `SecurityAccess.requireOwnerOrAdmin` against the payment's `ownerSubject`.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/payments` | ADMIN | Create a payment manually (`Idempotency-Key` replay returns `200` with the original) |
| GET | `/api/payments/{id}` | Owner | Payment by id |
| GET | `/api/payments/reference/{reference}` | Owner | Payment by reference |
| GET | `/api/payments/booking/{bookingId}` | Owner | Payment for a booking |
| GET | `/api/payments/{id}/history` | Owner | Payment state history |
| PATCH | `/api/payments/{id}/authorize` | Owner | Authorize the payment |
| PATCH | `/api/payments/{id}/capture` | Owner | Capture an authorized payment |
| PATCH | `/api/payments/{id}/cancel` | ADMIN | Cancel a payment |
| PATCH | `/api/payments/{id}/refund` | ADMIN | Refund (full or partial via optional body) |
| GET | `/api/refunds` | ADMIN | List all refunds |
| GET | `/api/refunds/{id}` | USER/ADMIN | Refund by id |
| GET | `/api/invoices/{paymentId}` | Owner | The passenger's receipt; `404` with explanation until the payment is captured |

## checkin-service

Back-office/gate operations are ADMIN at the URL level; the passenger self-service surface is owner-scoped in the controller (`CheckInAccessGuard`) and is exactly the surface a guest session may use.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/checkins` | ADMIN | Manual check-in record creation (normal path is the booking-confirmed Kafka consumer) |
| GET | `/api/checkins/{id}` | Owner/Guest | Check-in state by id |
| GET | `/api/checkins/booking/{bookingId}` | Owner/Guest | All check-ins for a booking |
| GET | `/api/checkins/flight/{flightId}` | ADMIN | Flight-scoped check-in listing |
| PATCH | `/api/checkins/{id}/open` | ADMIN | Open the check-in window |
| PATCH | `/api/checkins/{id}/checkin` | Owner/Guest | Perform the check-in |
| PATCH | `/api/checkins/{id}/board` | ADMIN | Board the passenger (gate operation) |
| PATCH | `/api/checkins/{id}/seat` | Owner/Guest | Change seat at check-in |
| PATCH | `/api/checkins/{id}/gate` | ADMIN | Assign a gate |
| GET | `/api/boarding-passes/{id}` | ADMIN | Boarding pass by id (gate/back-office lookup) |
| GET | `/api/boarding-passes/checkin/{checkInId}` | Owner/Guest | The passenger's own active pass for a check-in |
| GET | `/api/boarding-passes/verify` | ADMIN | Gate verification of a pass token; `422` on any verification failure |
| POST | `/api/boarding-passes/checkin/{checkInId}/email` | Owner/Guest | Email the pass to a caller-chosen address (`202`; throttled and audited) |
| POST | `/api/baggage` | Owner/Guest | Add baggage to a check-in |
| GET | `/api/baggage/checkin/{checkInId}` | Owner/Guest | Baggage for a check-in |
| GET | `/api/manifests/{flightId}` | ADMIN | Flight manifest |
| POST | `/api/manifests/{flightId}/finalize` | ADMIN | Manually finalize the manifest (scheduler covers the normal path) |

## Error shape

Every service — and the gateway itself — returns errors as the shared `ErrorResponse` record (`backend/skybook-common`, `com.skybook.praveen.common.exception.ErrorResponse`):

| Field | Type | Meaning |
|---|---|---|
| `timestamp` | ISO local date-time | When the error was produced |
| `status` | int | HTTP status code |
| `error` | string | HTTP reason phrase (e.g. `Not Found`, `Unauthorized`) |
| `message` | string | Human-readable detail |
| `path` | string | The request path that failed |

The same shape is emitted by each service's `GlobalExceptionHandler`, by the shared security handlers (`JsonAuthenticationEntryPoint` for `401`, `JsonAccessDeniedHandler` for `403`), and by the gateway's own filters:

| Status | Source | Trigger |
|---|---|---|
| `401 Unauthorized` | Gateway JWT filter | Missing, malformed, invalid, or expired token on a non-public path |
| `429 Too Many Requests` | Gateway rate-limit filter | Fixed-window rate limit exceeded (response carries `Retry-After: 60`) |
| `502 Bad Gateway` | Gateway downstream-error filter | Downstream service unreachable |
