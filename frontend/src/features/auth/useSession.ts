import { useSyncExternalStore } from 'react';
import { session, type CurrentUser } from '../../lib/session';

/**
 * Session state for components (FRONTEND_MODULE.md §4).
 *
 * `useSyncExternalStore` rather than context + effect: identity is mutated from
 * outside React (the API client clears it on a 401), so components read one
 * source of truth instead of caching a copy that can go stale.
 */
export interface SessionState {
  /** True once we know nobody is signed in, or who is. */
  resolved: boolean;
  signedIn: boolean;
  user: CurrentUser | null;
  /** Token subject - the normalised email, which is the ownership key. */
  subject: string | null;
  isAdmin: boolean;
}

function subscribe(listener: () => void): () => void {
  return session.subscribe(listener);
}

function snapshot(): CurrentUser | null | undefined {
  return session.current();
}

export function useSession(): SessionState {
  const user = useSyncExternalStore(subscribe, snapshot, () => undefined);

  // undefined means "we haven't asked yet". Distinguished from null so the UI
  // can hold off rather than flashing a signed-out header for one frame while
  // the /me call is in flight - a returning visitor with a valid cookie would
  // otherwise see themselves get logged out and back in on every page load.
  if (user === undefined) {
    return { resolved: false, signedIn: false, user: null, subject: null, isAdmin: false };
  }

  if (user === null) {
    return { resolved: true, signedIn: false, user: null, subject: null, isAdmin: false };
  }

  return {
    resolved: true,
    signedIn: true,
    user,
    subject: user.subject,
    // Cosmetic only: hides UI a passenger cannot use. Authorization is enforced
    // server-side, and the UI must never be what stands between a user and an
    // action (§4).
    isAdmin: user.roles.includes('ROLE_ADMIN'),
  };
}
