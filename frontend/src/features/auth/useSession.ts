import { useSyncExternalStore } from 'react';
import { decodeClaims, isExpired, session, type TokenClaims } from '../../lib/session';

/**
 * Session state for components (FRONTEND_MODULE.md §4).
 *
 * `useSyncExternalStore` rather than a context + effect: the token lives in
 * sessionStorage and is mutated from outside React (the API client clears it on
 * a 401). This keeps every component honest about that single source of truth
 * instead of caching a stale copy in state.
 */
export interface SessionState {
  signedIn: boolean;
  claims: TokenClaims | null;
  /** The token subject - the normalised email, which is also the owner key. */
  subject: string | null;
  isAdmin: boolean;
}

function snapshot(): string {
  return session.token() ?? '';
}

export function useSession(): SessionState {
  const token = useSyncExternalStore(session.subscribe, snapshot, () => '');

  if (token === '') {
    return { signedIn: false, claims: null, subject: null, isAdmin: false };
  }

  const claims = decodeClaims(token);

  // An expired token is treated as signed-out so the UI stops offering actions
  // that are certain to 401. The server would reject it anyway - this just
  // fails earlier and more clearly.
  if (!claims || isExpired(claims)) {
    return { signedIn: false, claims: null, subject: null, isAdmin: false };
  }

  return {
    signedIn: true,
    claims,
    subject: claims.sub,
    // Cosmetic only: hides UI a passenger cannot use. Authorization is enforced
    // server-side, and the UI must never be what stands between a user and an
    // action - see §4.
    isAdmin: claims.roles.includes('ROLE_ADMIN'),
  };
}
