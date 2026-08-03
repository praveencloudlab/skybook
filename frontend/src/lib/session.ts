/**
 * Session state (FRONTEND_MODULE.md §4, §10.1).
 *
 * <b>There is no token here.</b> The credential is an httpOnly cookie the
 * browser attaches automatically and JavaScript cannot read, which is the whole
 * security property: an XSS cannot exfiltrate what it cannot see.
 *
 * What remains client-side is only a cached answer to "who am I", obtained from
 * `GET /api/auth/me` - i.e. claims the SERVER validated, rather than a JWT the
 * browser decoded and hoped was genuine. It is display state, never authority:
 * every request is authorised server-side regardless of what this says.
 */

/** Where to return after signing in. Not a credential - just a path. */
const RETURN_TO_KEY = 'skybook.returnTo';

export interface CurrentUser {
  /** Token subject: the normalised email, which is also the ownership key. */
  subject: string;
  roles: string[];
}

type Listener = () => void;
const listeners = new Set<Listener>();

/**
 * Cached identity.
 *
 * In memory on purpose: it is derived state that must not outlive the page, and
 * persisting it would only create a second source of truth to go stale against
 * the cookie. `undefined` means "not yet asked", `null` means "asked, and nobody
 * is signed in" - a distinction the UI needs to avoid flashing a signed-out
 * state during the first load.
 */
let currentUser: CurrentUser | null | undefined;

function notify(): void {
  for (const listener of listeners) {
    listener();
  }
}

export const session = {
  /** undefined = not yet resolved; null = signed out. */
  current(): CurrentUser | null | undefined {
    return currentUser;
  },

  set(user: CurrentUser | null): void {
    currentUser = user;
    notify();
  },

  /**
   * Forget the cached identity.
   *
   * This does NOT sign the user out - the cookie is httpOnly, so only the server
   * can clear it (`POST /api/auth/logout`). Used when a 401 tells us the session
   * is already gone.
   */
  clear(): void {
    currentUser = null;
    notify();
  },

  /**
   * Where to send the user back to after signing in.
   *
   * Kept so an expiry mid-journey does not dump them on a blank page - losing a
   * half-filled passenger form to a 401 is the kind of thing people do not
   * forgive. Safe to persist: it is a path, not a credential.
   */
  setReturnTo(path: string): void {
    sessionStorage.setItem(RETURN_TO_KEY, path);
  },

  takeReturnTo(): string | null {
    const value = sessionStorage.getItem(RETURN_TO_KEY);
    sessionStorage.removeItem(RETURN_TO_KEY);
    return value;
  },

  subscribe(listener: Listener): () => void {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },
};
