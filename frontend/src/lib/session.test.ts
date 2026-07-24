import { beforeEach, describe, expect, it, vi } from 'vitest';
import { decodeClaims, isExpired, session } from './session';

/** Build an unsigned JWT-shaped token. Signature is irrelevant - we never verify. */
function tokenWith(payload: Record<string, unknown>): string {
  const encode = (obj: unknown) =>
    btoa(JSON.stringify(obj)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${encode({ alg: 'RS256' })}.${encode(payload)}.signature`;
}

describe('session', () => {
  beforeEach(() => sessionStorage.clear());

  it('stores the token in sessionStorage and never writes it to disk', () => {
    // §10.1: a 60-minute token with no server-side revocation must not persist
    // to disk, so a shared machine leaves no live session behind.
    //
    // Asserted by spying rather than by reading localStorage, because Node 26
    // ships its OWN experimental localStorage that shadows jsdom's and is
    // undefined unless --localstorage-file is passed. Spying also states the
    // invariant more directly: we never CALL localStorage.setItem at all.
    const setItem = vi.fn();
    vi.stubGlobal('localStorage', { setItem, getItem: vi.fn(() => null), removeItem: vi.fn() });

    session.setToken('a.b.c');

    expect(sessionStorage.getItem('skybook.token')).toBe('a.b.c');
    expect(setItem).not.toHaveBeenCalled();

    vi.unstubAllGlobals();
  });

  it('notifies subscribers when the token changes', () => {
    const listener = vi.fn();
    const unsubscribe = session.subscribe(listener);

    session.setToken('a.b.c');
    session.clear();
    unsubscribe();
    session.setToken('d.e.f');

    expect(listener).toHaveBeenCalledTimes(2); // not 3 - unsubscribed before the last
  });

  it('returns returnTo once, then forgets it', () => {
    session.setReturnTo('/bookings/7');

    expect(session.takeReturnTo()).toBe('/bookings/7');
    // Consumed - otherwise a later sign-in would bounce somewhere unexpected.
    expect(session.takeReturnTo()).toBeNull();
  });

  it('decodes claims from a base64url payload', () => {
    const claims = decodeClaims(tokenWith({ sub: 'a@b.com', roles: ['ROLE_USER'], exp: 123 }));

    expect(claims).toEqual({ sub: 'a@b.com', roles: ['ROLE_USER'], exp: 123 });
  });

  it('returns null for a malformed token instead of throwing', () => {
    // A parse error here must not take a screen down.
    expect(decodeClaims('not-a-jwt')).toBeNull();
    expect(decodeClaims('a.!!!not-base64!!!.c')).toBeNull();
  });

  it('detects expiry, treating a missing exp as not expired', () => {
    const past = Math.floor(Date.now() / 1000) - 60;
    const future = Math.floor(Date.now() / 1000) + 3600;

    expect(isExpired({ sub: 'a', roles: [], exp: past })).toBe(true);
    expect(isExpired({ sub: 'a', roles: [], exp: future })).toBe(false);
    expect(isExpired({ sub: 'a', roles: [] })).toBe(false);
  });
});
