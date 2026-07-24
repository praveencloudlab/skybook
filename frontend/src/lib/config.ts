/**
 * Runtime configuration (FRONTEND_MODULE.md §1.1, §2).
 *
 * The gateway is the ONLY host the browser ever talks to. Individual services
 * are not host-published at all since the security-hardening branch, so there is
 * no "call payment-service directly" option even if we wanted one - and that is
 * the correct trust boundary regardless.
 */

/**
 * Where the API lives, relative to this page.
 *
 * <b>Empty by default, and that is the point.</b> Requests go to `/api/...` on
 * our OWN origin, which a proxy forwards to the gateway - the Vite dev server in
 * development, nginx in the container. Same-origin is what makes the httpOnly
 * session cookie usable at all (§10.1): it lets the cookie be `SameSite=Lax`,
 * which is a genuine CSRF control, instead of `SameSite=None`, which is sent
 * cross-site and would need CSRF tokens of its own.
 *
 * `VITE_API_BASE_URL` can still point the app at an absolute gateway URL, but
 * doing so goes back to being cross-origin: the cookie would not be sent, and
 * only bearer-token callers would work. It exists for diagnostics, not as the
 * normal path.
 */
export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? '';

/**
 * How long to keep polling an async journey step before giving up (§3).
 *
 * Three steps of the journey resolve over Kafka, so the resource genuinely does
 * not exist yet when the previous call returns. 45s matches the bound the e2e
 * suite proved generous enough on slow CI runners.
 */
export const ASYNC_TIMEOUT_MS = 45_000;

/**
 * Poll backoff, in milliseconds (§3).
 *
 * Deliberately NOT a fixed 500ms interval: the gateway rate-limits at 100
 * req/min, and the e2e suite tripped exactly that by polling twice a second -
 * the limiter policing the client rather than a real abuse case. Backing off
 * keeps a long wait well inside the budget even with several tabs open.
 */
export const POLL_BACKOFF_MS = [1_000, 2_000, 4_000, 4_000, 8_000] as const;
