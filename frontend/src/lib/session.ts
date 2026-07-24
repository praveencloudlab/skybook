/**
 * Session storage (FRONTEND_MODULE.md §4, §10.1).
 *
 * <b>sessionStorage, not localStorage</b> — a decision, not a default. The token
 * is a 60-minute bearer credential with NO server-side revocation (refresh and
 * revocation are explicitly deferred), so the only real control over its
 * lifetime is where it is kept. Scoping it to the tab means a shared machine
 * leaves no live session behind, and an XSS cannot resurrect one from disk.
 * Accepted cost: opening a new tab means signing in again.
 */

const TOKEN_KEY = 'skybook.token';
const RETURN_TO_KEY = 'skybook.returnTo';

/** Notified whenever the token changes, so React state can follow it. */
type Listener = () => void;
const listeners = new Set<Listener>();

function notify(): void {
  for (const listener of listeners) {
    listener();
  }
}

export const session = {
  token(): string | null {
    return sessionStorage.getItem(TOKEN_KEY);
  },

  setToken(token: string): void {
    sessionStorage.setItem(TOKEN_KEY, token);
    notify();
  },

  clear(): void {
    sessionStorage.removeItem(TOKEN_KEY);
    notify();
  },

  isSignedIn(): boolean {
    return sessionStorage.getItem(TOKEN_KEY) !== null;
  },

  /**
   * Where to send the user back to after signing in.
   *
   * Kept so an expiry mid-journey does not dump them on a blank page - losing a
   * half-filled passenger form to a 401 is the kind of thing people do not
   * forgive.
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

/** Claims we read from the token. Never trusted for security - see below. */
export interface TokenClaims {
  sub: string;
  roles: string[];
  exp?: number;
}

/**
 * Decode the token payload WITHOUT verifying it.
 *
 * Verification is deliberately absent: the gateway and every service already
 * verify RS256 properly, and a browser holding a verification key proves
 * nothing. These claims are used only to show the right things (a name, an admin
 * link) - <b>never</b> to decide what the user may do. The server is the only
 * authority on that, and it enforces the §4.4 matrix regardless of what the UI
 * believes.
 */
export function decodeClaims(token: string): TokenClaims | null {
  const parts = token.split('.');
  if (parts.length < 2) {
    return null;
  }
  try {
    const payload = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const json = JSON.parse(atob(payload)) as Partial<TokenClaims>;
    if (typeof json.sub !== 'string') {
      return null;
    }
    return {
      sub: json.sub,
      roles: Array.isArray(json.roles) ? json.roles : [],
      exp: typeof json.exp === 'number' ? json.exp : undefined,
    };
  } catch {
    return null;
  }
}

/**
 * True when the token is already past its expiry.
 *
 * Lets the UI send someone to sign-in before firing a request that is certain to
 * come back 401 — a nicer failure than a spinner that resolves into an error.
 */
export function isExpired(claims: TokenClaims | null): boolean {
  if (!claims?.exp) {
    return false;
  }
  return claims.exp * 1000 <= Date.now();
}
