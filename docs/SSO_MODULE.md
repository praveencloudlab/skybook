# 🔑 SkyBook SSO — Sign in with Google (OIDC) — Design

## Project Information

| | |
|---|---|
| **Author** | Praveenreddy Somireddy |
| **Status** | FROZEN 2026-08-05 — D1–D4 accepted as recommended; build order in flight |
| **Scope** | Passenger sign-in via Google (OpenID Connect, authorization code + PKCE) |
| **Depends on** | SECURITY_HARDENING_MODULE.md (implemented: RS256, gateway translation point, ownership) |
| **Explicitly out of scope** | Staff/admin SSO via a corporate IdP (deferred, §11) · account-settings link/unlink UI · refresh tokens · SSO inside the mid-booking inline gate (§2.8) · providers beyond Google |

# 1. Overview

A passenger can sign in (and implicitly register) with their Google account.
One sentence governs the whole design:

> **Google authenticates; SkyBook authorizes.** The external identity is
> exchanged, inside auth-service, for the same RS256 SkyBook token every other
> sign-in produces — no Google token, claim, or session ever crosses
> auth-service's boundary.

Consequences of that sentence, stated up front:

- The gateway, the seven domain services, the ownership rules, the audience
  model, the e2e suite, and every downstream consumer of
  `Authorization: Bearer` **do not change and do not know SSO exists**.
- Future auth work (key rotation, refresh tokens, a second IdP) happens behind
  the same exchange point. The gateway filter's own comment anticipated this:
  the browser credential "could become … an OIDC session … only THIS method
  changes" (`JwtAuthenticationFilter`, api-gateway).
- SSO is **off unless configured** (§6). Every CI rung, the nightly
  certification, and a fresh clone behave exactly as today with zero Google
  dependency.

# 2. Load-Bearing Findings (traced against the live code, not assumed)

## 2.1 The exchange point already exists

`JwtService.generateToken(email, role)` mints the user token from exactly two
inputs, with no assumption about *how* the caller authenticated. The OIDC
success path calls this same method. No new token shape, no new claims —
deliberately: the token states who you are, never how you proved it.

## 2.2 The cookie mechanics already fit an OAuth callback

`SessionCookie.issue(token, remember)` builds the `skybook_session` httpOnly
cookie; `AuthController.login` attaches it to the response. The OIDC callback
is a **top-level GET navigation** (Google redirecting the browser back), and
`SameSite=Lax` cookies are both *sent* and *settable* on top-level
navigations — so the callback response can set the session cookie exactly the
way password login does. No relaxation to `SameSite=None`, no CSRF-posture
change.

## 2.3 The password column is already nullable

`users.password` is `varchar(255)` with no NOT NULL (V1 baseline), and the
entity mirrors that. A Google-only account is a row with `password = NULL`.
No destructive migration — V7 only *adds* a table (§4.1).

## 2.4 …but the login timing defence assumes a hash is present

`AuthService.login` picks `user.getPassword()` as the hash to check. For a
Google-only account that is `null`; Spring's `BCryptPasswordEncoder.matches`
short-circuits on a null/empty encoded password and returns `false` **without
doing BCrypt work**. Same generic 401 — but measurably faster, which
re-opens the account-enumeration channel §6 of the hardening design closed:
an attacker could distinguish "Google-only account exists" from "wrong
password". **Fix (build step 2): fall back to `dummyPasswordHash` whenever
the stored hash is null, keeping every failure path BCrypt-shaped.**

## 2.5 The stateless constraint is real, and oauth2Login defaults violate it

Auth-service's application chain holds no HTTP session (nothing creates one);
Spring Security's `oauth2Login` defaults to storing the in-flight
authorization request (state, nonce, PKCE verifier) **in the servlet
session**. Accepting that would make auth-service sticky and stateful — a
regression. The design replaces it with a **cookie-based
`AuthorizationRequestRepository`** (§3.3): the pending request rides in a
short-lived, httpOnly, `SameSite=Lax` cookie, and the service stays
stateless. The chain also gains an explicit
`SessionCreationPolicy.STATELESS`, turning the convention into a stated rule.

## 2.6 The gateway's explicit-path doctrine applies

`/api/auth/**` is deliberately never wildcarded in the route table, so
`/api/auth/service-token` stays off the public edge. SSO adds **three** new
public paths, each listed explicitly at every gate (§5): the start endpoint,
the callback, and the provider-discovery endpoint.

## 2.7 `app.public-base-url` already exists per environment

The password-reset feature established the property (`APP_PUBLIC_BASE_URL`,
derived from `SKYBOOK_DOMAIN` in the prod overlay) and fixed its
localhost-in-prod bug. The OIDC redirect URI is built from the same property
(§6.2) — **never** from request headers, so redirect-URI correctness can't
depend on forwarded-header trust.

## 2.8 Two frontend findings that shape the UI

- `useSession` resolves identity by calling `/me` on load. A callback that
  302s the browser back to the SPA with the cookie set signs the user in with
  **zero new frontend session code**.
- The booking funnel's state is in-memory; the inline `SignInForm` shown
  mid-booking works precisely because it doesn't navigate. An OAuth redirect
  is a full navigation and would destroy the funnel. **The Google button
  therefore appears on the standalone sign-in/register pages only** (v1);
  the inline gate is untouched (D3, §10).

# 3. The Flow

## 3.1 Sequence

```
Browser                Gateway              auth-service                Google
   |  GET /api/auth/oauth2/authorization/google?remember=&returnTo=      |
   |──────────────────────►|────────────────────►|                       |
   |                       |   302 → accounts.google.com (+ Set-Cookie:  |
   |◄──────────────────────|◄────────────────────|   pending-auth cookie)|
   |  consent at Google ───────────────────────────────────────────────►|
   |◄───────────────────────────── 302 → /api/auth/oauth2/callback/google?code=&state=
   |──────────────────────►|────────────────────►|  validate state+nonce (cookie)
   |                       |                     |  POST token endpoint (+PKCE verifier)
   |                       |                     |  verify ID token (Google JWKS)
   |                       |                     |  find-or-link-or-provision (§4)
   |                       |                     |  mint SkyBook RS256 token (§2.1)
   |◄──────────────────────|◄────────────────────|  302 → returnTo
   |                       |                     |  + Set-Cookie: skybook_session
   |  GET /me (SPA boot) → signed in                                     |
```

## 3.2 Endpoints

| Path | Method | Purpose |
|---|---|---|
| `/api/auth/oauth2/authorization/google` | GET | Start. Accepts `remember` (boolean, default false) and `returnTo` (same-origin path, default `/`). 302 to Google. When SSO is disabled: 302 to `/login?error=sso_unavailable`. |
| `/api/auth/oauth2/callback/google` | GET | Redirect target registered with Google. Success: sets session cookie, 302 to `returnTo`. Failure: 302 to `/login?error=<code>` (§7). |
| `/api/auth/sso/providers` | GET | Public discovery: `["google"]` or `[]`. Exists because the frontend image is built once and promoted everywhere (build-once), so provider availability must be **runtime** data, not a build flag. |

Spring's default URIs (`/oauth2/authorization/*`, `/login/oauth2/code/*`) are
re-based under `/api/auth/` via the `oauth2Login` DSL so gateway routing and
the explicit-path doctrine stay uniform.

## 3.3 The pending-request cookie

Custom `AuthorizationRequestRepository` storing **the per-flow secrets only**
— state, nonce, PKCE verifier — plus SkyBook's two carried values
(`remember`, `returnTo`). Configuration (client id, scopes, redirect URI,
authorization URI) is **rebuilt from the registration at load time, never
round-tripped through the browser**.

That rule was learned live, and is recorded here at full volume: the first
implementation sealed the whole Java-serialized `OAuth2AuthorizationRequest`
and produced a **~4.5 KB cookie** — past nginx's default `proxy_buffer_size`
(a 502 at the frontend's `/api` hop, the flow's very first production
request) and past the **browser's 4096-byte per-cookie cap** (a silently
dropped cookie, which would have made every callback fail even with nginx
appeased). Both limits live outside the JVM, which is how a MockMvc flow
test missed them; the repository test now pins the sealed size (< 1200
bytes; actual ≈ 400) so the property cannot regress unnoticed.

- httpOnly, `Secure`, `SameSite=Lax`, path `/api/auth/oauth2/`, max-age
  **5 minutes** — the consent screen's lifetime, not a session.
- Payload is AES-GCM-encrypted with a key derived from the JWT private key
  material already present (no new secret), preventing tamper and disclosure.
- Consumed (expired) on callback, success or failure — one shot.

`returnTo` is validated at **write** time and again at **read** time: it
must match `^/(?!/)[A-Za-z0-9/._~?=&%-]*$`. The `(?!/)` is load-bearing and
easy to miss: a bare "starts with `/`" check admits `//evil.com`, which
browsers resolve as a *protocol-relative* URL to another host — the classic
open-redirect via redirect-path validation. One slash, never two; no scheme,
no host, no backslash (some browsers treat `/\` like `//`). Anything that
fails becomes `/`. Caught in this design's own self-audit, which is why the
rule is stated with its counter-example.

## 3.4 PKCE, state, nonce

All three, always: `state` (CSRF binding, Spring), `nonce` (OIDC replay
binding, Spring), and PKCE S256 via `OAuth2AuthorizationRequestCustomizers
.withPkce()` — Google supports PKCE for confidential clients, and a
confidential client with PKCE is strictly stronger (a leaked authorization
code alone becomes worthless).

# 4. Identity Model and Linking

## 4.1 Schema — V7

```sql
CREATE TABLE federated_identities (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES users(id),
    provider      VARCHAR(20)  NOT NULL,           -- 'google'
    subject       VARCHAR(255) NOT NULL,           -- Google's stable 'sub'
    email_at_link VARCHAR(255) NOT NULL,           -- forensic record only
    linked_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_provider_subject UNIQUE (provider, subject),
    CONSTRAINT uq_user_provider    UNIQUE (user_id, provider)
);
```

`users` is untouched. The identity's key is `(provider, subject)` — **never
email**, because Google's `sub` is stable for life while the email on a
Google account can change.

## 4.2 The callback decision tree (frozen)

```
ID token verified (signature via Google JWKS, iss, aud, exp, nonce)
│
├─ 1. SELECT by (provider='google', sub)
│     └─ found → sign in that user. Done.
│        (If Google now reports a different email: the SkyBook account and
│         its email are unchanged — identity is sub, email is contact info.)
│
├─ 2. email_verified != true → REJECT → /login?error=sso_email_unverified
│     (an unverified Google email proves nothing; trusting it would let
│      anyone claim any address)
│
├─ 3. normalize(email) — trim().toLowerCase(ROOT), same as register/login —
│     then SELECT user by email
│     ├─ found → LINK: insert federated_identity, sign in  (D1: auto-link
│     │   on verified email — decision log)
│     └─ not found → PROVISION:
│         user(fullName = ID-token 'name', or the email local part when
│              Google omits name;
│              email = normalized; password = NULL; role = USER)
│         insert federated_identity
│         emit REGISTRATION_SUCCESS welcome email (same event as register)
│         sign in
│
└─ Race (two first-sign-ins, or link vs register):
      INSERT hits uq_provider_subject or the users email unique index →
      catch DataIntegrityViolationException, re-run step 1/3 lookup, sign in
      the winner's row — the same translate-the-race pattern register() uses.
```

## 4.3 Google-only accounts across the existing surface (traced per flow)

| Flow | Behaviour with `password = NULL` | Change needed |
|---|---|---|
| Password login | Generic 401 — **after the dummy-hash fix (§2.4)** with full BCrypt timing | Step 2 |
| Forgot / reset password | Works unchanged: reset **sets** a password; the account becomes dual-method (Google *and* password). This is the supported "I want a password" path — it already exists and needs no code. | None |
| Change password (signed in) | `matches(current, null)` → false → the same "current password incorrect" 400. Honest and safe; copy untouched in v1. | None |
| `/me`, profile, saved travellers, bookings, ownership | Key on the normalized email subject — identical tokens, identical claims | None |
| Logout | Same cookie, same expiry endpoint | None |
| Admin bootstrap (`SKYBOOK_BOOTSTRAP_ADMIN_EMAIL`) | Promotion is by email lookup — works for a provisioned account too | None |

# 5. Gates (the explicit-path doctrine applied)

Every new public path is added at **each** gate, by name, no wildcards:

1. **Gateway route table** (`GatewayRoutesConfig.authServiceRoute`): the three
   paths join the explicit list.
2. **Gateway `PUBLIC_PATHS`** (`JwtAuthenticationFilter`): same three, exact
   patterns.
3. **auth-service chain** — all **three** paths join `permitAll`
   **statically, whether or not SSO is enabled** — the gate list must never
   depend on runtime feature state, or the disabled world and the enabled
   world would have different security surfaces. When enabled, `oauth2Login`
   owns the start/callback paths; when disabled, a
   `@ConditionalOnProperty`-guarded **fallback controller** owns the same two
   mappings and answers each with the `sso_unavailable` redirect (§7) —
   without it, a disabled-mode click would fall through to the JWT filter
   and surface as a raw JSON 401 to a *browser mid-navigation*. The chain
   gains explicit `STATELESS` (§2.5).

`/api/auth/service-token` remains unrouted from the edge, untouched.

# 6. Configuration and Environments

## 6.1 Properties

```yaml
skybook:
  sso:
    google:
      client-id: ${GOOGLE_CLIENT_ID:}          # empty ⇒ SSO disabled
      client-secret: ${GOOGLE_CLIENT_SECRET:}
```

The Spring `ClientRegistrationRepository` is built **programmatically**, only
when `client-id` is non-empty (`@ConditionalOnProperty`-guarded config). With
it absent: no oauth2 beans, the start endpoint answers with the
`sso_unavailable` redirect, `/api/auth/sso/providers` returns `[]`, the
frontend button hides. **A missing env var can never fail a boot** — the
placeholder has an empty default, unlike the required-secret pattern used for
service credentials, because SSO is a feature, not an invariant.

## 6.2 Redirect URI

`${app.public-base-url}/api/auth/oauth2/callback/google` — built from the
property, never from `Host`/`X-Forwarded-*`. Registered in the Google console
per environment:

| Environment | Registered redirect URI |
|---|---|
| LOCAL (docker) | `http://localhost:3000/api/auth/oauth2/callback/google` |
| LOCAL (vite dev) | `http://localhost:5173/api/auth/oauth2/callback/google` |
| PROD (today) | `https://145.241.236.180.sslip.io/api/auth/oauth2/callback/google` |
| PROD (after the domain move) | `https://<domain>/api/auth/oauth2/callback/google` |

Google requires exact matches, so the console list simply grows/shrinks with
the estate; nothing in the codebase changes when the domain does.

## 6.3 The ladder

DEV/SIT/QA/PERF/nightly/e2e: **no Google variables set → SSO off → zero new
behaviour.** The certification suite continues to prove the password journey.
The SSO integration tests (§8) run in the unit/failsafe tier against a
stubbed provider, so the artifact's SSO code is exercised by CI without any
external dependency, and the four rungs stay hermetic.

Secrets inventory: `GOOGLE_CLIENT_ID` (config, low-sensitivity),
`GOOGLE_CLIENT_SECRET` (secret; `.env` on host machines only — it is needed
at *runtime* on whatever host serves the flow, and nowhere else; CI never
holds it).

## 6.4 One-time setup (owner's checklist)

1. console.cloud.google.com → new project *skybook* → **OAuth consent
   screen**: External, app name SkyBook, your email; add yourself as a test
   user (test mode is sufficient — no verification review needed).
2. **Credentials → Create credentials → OAuth client ID → Web application**;
   add the redirect URIs from §6.2.
3. Put the issued client id + secret in `.env` (local) and the VM's `.env`
   (prod). Restart auth-service. The button appears; done.

# 7. Failure Paths (frozen)

Every failure lands the browser on `/login?error=<code>` — never a JSON
error page, because the caller is a *browser mid-navigation*, not an API
client. The SPA maps codes to copy:

| Code | Trigger | UI copy (SignInPage alert) |
|---|---|---|
| `sso_cancelled` | User hit Cancel/Back at Google (`error=access_denied`) | "Google sign-in was cancelled." |
| `sso_email_unverified` | `email_verified` false | "Your Google email address isn't verified, so we can't use it to sign you in." |
| `sso_unavailable` | Start endpoint hit while disabled | "Google sign-in isn't available right now." |
| `sso_failed` | Everything else: state mismatch, expired/absent pending cookie (>5 min at the consent screen), token-endpoint error, JWKS failure, invalid ID token | "Google sign-in didn't complete. Please try again or use your password." |

Deliberately **one generic bucket** for the technical failures: the
distinctions are logged server-side (with the OAuth error code) for
operators, but a passenger can act on none of them, and enumerating internals
in a URL parameter is surface for no gain.

Availability note: Google being down breaks *new Google sign-ins only* —
password sessions and every signed-in user are unaffected. No circuit breaker
needed; the failure is user-visible, retryable, and rare.

# 8. Testing Strategy

- **Unit** (surefire): the decision tree (§4.2) — found-by-sub /
  unverified-reject / link / provision / both race arms; `returnTo`
  validation table (evil inputs → `/`); dummy-hash timing fix (a null-hash
  login exercises the encoder exactly once).
- **Integration** (failsafe, in-service): WireMock stands in for Google
  (authorization, token, JWKS endpoints; provider URIs overridden in test
  properties; test-signed JWKs). Full-flow assertions: 302 to Google carries
  state+nonce+PKCE; callback sets `skybook_session` with/without Max-Age per
  `remember`; the minted token validates with the fleet's own
  `JwtTokenValidator`; disabled-mode returns `sso_unavailable` and `[]`.
- **Frontend** (vitest): button renders iff providers contains `google`;
  every §7 error code renders its copy; `remember` checkbox state reaches the
  start URL.
- **Not covered and stated**: a live round trip against real Google is a
  manual step in build order step 8 (a headless CI cannot pass a Google
  consent screen; pretending otherwise would be theatre).

# 9. Build Order

Each step compiles, tests green, and is independently verifiable:

1. **V7 migration + entity/repository** (`FederatedIdentity`,
   `FederatedIdentityRepository`) — schema exists, nothing uses it.
2. **Login timing fix** (§2.4) + its unit test — a standalone hardening fix
   that is correct with or without SSO.
3. **Stateless scaffolding**: explicit `STATELESS` on the application chain;
   the encrypted cookie `AuthorizationRequestRepository` + round-trip tests.
4. **The OIDC core**: conditional registration config, success handler
   (decision tree → `generateToken` → `SessionCookie.issue` → redirect),
   failure handler (§7 mapping), `/api/auth/sso/providers`.
5. **Gateway gates**: route table + `PUBLIC_PATHS` additions.
6. **Frontend**: providers fetch, Google button on SignIn/Register (standalone
   pages only, §2.8), error-code copy, remember/returnTo wiring.
7. **Certification-adjacent**: full backend + frontend suites green; Sonar
   quality gate holds (new code covered ≥ 80%).
8. **Live verification**: owner performs §6.4; real Google round trip on
   LOCAL (new-user provision, then link to an existing password account,
   then password login on the Google-only account → 401, then
   forgot-password sets one → dual). Then the prod redirect URI when
   promoted.

# 10. Decision Log

| # | Decision | Recommendation & reasoning | Status |
|---|---|---|---|
| D1 | **Auto-link a Google sign-in to an existing password account with the same verified email** | **Yes.** Industry default (the alternative — a "this email already has an account, sign in with your password to link" interstitial — defends against an attacker who controls the *Google account* of a victim's email, a compromise that already loses the account via forgot-password). Verified-email is the load-bearing predicate; unverified is rejected outright. | Proposed |
| D2 | **Google only, passengers only** | **Yes.** One provider proves the architecture; the exchange boundary makes the second provider a config-plus-one-enum change. Staff SSO (Keycloak) is a separate feature with its own design (§11). | Proposed |
| D3 | **Button on standalone auth pages only, not the inline booking gate** | **Yes.** The OAuth redirect destroys in-memory funnel state (§2.8). Persisting the funnel to survive navigation is real work with its own edge cases — a future increment, not a rider. | Proposed |
| D4 | **Runtime provider discovery endpoint, not a build-time flag** | **Yes.** The frontend image is built once and promoted; a build flag would fork the artifact per environment and break the ladder's core principle. | Proposed |

# 11. Deferred, Explicitly

- **Staff/admin SSO** against a corporate IdP (Keycloak locally): separate
  design; different trust model (IdP-asserted roles vs SkyBook-owned roles).
- **Account settings: view/unlink linked identities, set password while
  signed in** (today's supported path is forgot-password).
- **Refresh tokens / session extension** — pre-existing gap
  (SECURITY_HARDENING_MODULE.md §14), unchanged by SSO; a Google session does
  not extend a SkyBook session.
- **SSO in the booking funnel** — needs funnel-state persistence first (D3).
- **Second consumer provider** (Apple is the usual pairing) — after the
  pattern proves out.
