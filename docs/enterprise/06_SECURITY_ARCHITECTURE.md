# SKB-DOC-06 — Security Architecture

| | |
|---|---|
| **Document ID** | SKB-DOC-06 |
| **Version** | 1.0 |
| **Status** | Baselined |
| **Owner** | Platform Engineering |
| **Effective date** | 2026-08-01 |
| **Design source** | `SECURITY_HARDENING_MODULE.md` (frozen design); this document is the as-built summary developers code against |

## 1. Identity & token model

- **Issuer:** auth-service, and only auth-service, signs tokens — **RS256**
  with the private key held solely by it; every other service and the
  gateway verify with the public key. Keys are injected via `.env`
  (`JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY`); there is no HS256 and no shared
  signing secret anywhere.
- **Claims:** subject (user e-mail / service client id), exactly one role,
  issuer `skybook-auth`, audience `skybook-api`. The validator enforces the
  full tuple; a token with a surprising role *set* (e.g. USER+SERVICE) is
  rejected outright.
- **Roles:**
  - `ROLE_USER` — passengers. Object-level ownership applies (§3).
  - `ROLE_ADMIN` — back office. May read/act across customers; explicitly
    bypasses passenger-side time windows where the SRS says so (FR-CANX-06).
    No self-service path to ADMIN: bootstrap promotion only via
    `SKYBOOK_BOOTSTRAP_ADMIN_EMAIL` at auth-service start.
  - `ROLE_SERVICE` — machine identity for service→service calls (§2).
- **Login contract:** `POST /api/auth/login` returns the raw JWT as
  text/plain. Logout is client-side token disposal plus server-side session
  cleanup for SSO.
- **SSO:** "Sign in with Google" per `SSO_MODULE.md`; the transient session
  cookie is `__Host-` prefixed (requires Secure; browsers drop it
  otherwise — a documented production lesson).

## 2. Service-to-service authentication

Outbound Feign calls attach a short-lived `ROLE_SERVICE` token obtained from
auth-service's internal token endpoint using **per-service client
credentials** (`*_CLIENT_SECRET` env vars; auth-service stores hashes).
Tokens are audience-targeted and cached until near expiry
(`ServiceTokenProvider`). Rule for developers: code running on a Kafka
consumer thread has **no user token to propagate** — any synchronous
enrichment call from an event handler must use the service token path
(`getFlightAsService` is the precedent), never a stashed user credential.

## 3. Object-level authorisation (the ownership rule)

Every booking, payment, and check-in row snapshots its owner's subject at
creation (`ownerSubject`, carried on every event). Guards
(`requireOwnerOfBooking`, `requireOwnerOfCheckIn`, …) compare the token
subject against the row **in the owning service** — never trusting a
client-supplied id — and `isPrivileged()` (ADMIN or SERVICE) is the only
bypass. List endpoints are owner-scoped by construction (`/mine` reads the
subject from the token; there is no id parameter to tamper with).

Guest check-in gets a scoped session (booking + surname proof) that grants
access to exactly one booking's check-in operations; guests must never hit
the global 401→sign-in redirect (`GUEST_CHECKIN_MODULE.md`).

## 4. Edge security

- The gateway verifies tokens for everything outside the public-path
  allow-list (SKB-DOC-04 §2.1) — and each owning service **verifies again**;
  the gateway is a convenience layer, not the trust boundary. Making an
  endpoint public requires all four gates in one PR (route table, gateway
  allow-list, service SecurityConfig, fan-out check).
- The SPA's nginx proxies `/api` same-origin and strips the `Origin` header
  (auth is Bearer, not cookies, so CORS adds nothing on that path and the
  app deploys on any domain). Gateway CORS remains only for direct dev-time
  origins.
- Production exposes exactly one door: Caddy on 80/443 with automatic TLS;
  every operational port binds to 127.0.0.1 (SSH tunnel access) —
  `docker-compose.prod.yml`.

## 5. Secrets

All secrets arrive via `.env`/environment and are **fail-fast**: compose
uses `${VAR:?}` so a missing secret aborts startup; checkin-service
additionally refuses its HMAC key if it is short or equals the old dev
default. Inventory of secrets and generation commands: `env.example` and
`docs/DEPLOY_ORACLE.md` §4. Never commit a real value; `.env` is gitignored.
Password hashing: BCrypt. Card data: none exists in the system (§7 of
SKB-DOC-05).

## 6. Signed artefacts

Boarding-pass QR tokens are HMAC-signed by checkin-service
(`CHECKIN_BOARDING_PASS_KEY`, ≥32 bytes). Verification happens server-side
at the admin verify endpoint; a revoked or reissued pass fails verification.
Reissue (post-check-in seat change) invalidates the prior pass atomically
with issuing the new one.

## 7. Security engineering rules for developers

1. New endpoint ⇒ decide its class first: public (four gates), USER-owned
   (add the ownership guard), ADMIN, or SERVICE. There is no fifth class.
2. Never log tokens, secrets, or full PII; passenger names/PNRs are
   acceptable in logs, passport numbers are not.
3. Any new cross-service call ⇒ service-token path + resilience wrapper
   (SKB-DOC-02 §5); never a raw RestTemplate.
4. AuthZ failures return 403 with an honest message; existence-probing reads
   return 404 for objects the caller cannot see.
5. Every security-relevant change updates this document's register in the
   same PR (SKB-DOC-04 §6 applies equally here).

## 8. Known posture notes

- TLS terminates at Caddy; in-VM traffic between containers is plain HTTP on
  the compose network (accepted for the single-host topology; revisit for
  Kubernetes).
- Rate limiting at the edge is not yet implemented (backlog; mitigations:
  bookable cutoffs, idempotency keys, optimistic locks bound the damage of
  replayed writes).
- The CSRF suppressions flagged by static analysis are reviewed and
  justified (Bearer-token API, no cookie-authenticated state changes) — see
  the Sonar gate record in `project_sonar_gate` history.
