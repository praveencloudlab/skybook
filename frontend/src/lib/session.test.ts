import { beforeEach, describe, expect, it, vi } from 'vitest';
import { session } from './session';

describe('session', () => {
  beforeEach(() => {
    sessionStorage.clear();
    session.clear();
  });

  it('never stores a credential anywhere', () => {
    // The whole point of §10.1: the credential is an httpOnly cookie the browser
    // holds and JS cannot read. Asserted by spying, so this fails loudly if
    // anyone reintroduces token storage - and because Node 26 ships its own
    // experimental localStorage that shadows jsdom's and is undefined without
    // --localstorage-file, making a direct read unreliable here.
    const localSet = vi.fn();
    const sessionSet = vi.spyOn(Storage.prototype, 'setItem');
    vi.stubGlobal('localStorage', { setItem: localSet, getItem: () => null, removeItem: vi.fn() });

    session.set({ subject: 'a@b.com', roles: ['ROLE_USER'] });

    expect(localSet).not.toHaveBeenCalled();
    // The only thing we ever persist is a return path - never a credential.
    for (const call of sessionSet.mock.calls) {
      expect(call[0]).toBe('skybook.returnTo');
    }

    sessionSet.mockRestore();
    vi.unstubAllGlobals();
  });

  it('distinguishes "not yet asked" from "signed out"', async () => {
    // undefined vs null is load-bearing: it stops the UI flashing a signed-out
    // header while /me is still in flight for a returning visitor.
    //
    // Loaded fresh, because "not yet asked" is the module's INITIAL state and
    // any earlier set()/clear() in this file would have already resolved it -
    // testing it on the shared instance would only prove the last call won.
    vi.resetModules();
    const { session: fresh } = await import('./session');

    expect(fresh.current()).toBeUndefined();

    fresh.set(null);
    expect(fresh.current()).toBeNull();

    fresh.set({ subject: 'a@b.com', roles: ['ROLE_USER'] });
    expect(fresh.current()).toEqual({ subject: 'a@b.com', roles: ['ROLE_USER'] });
  });

  it('notifies subscribers when identity changes', () => {
    const listener = vi.fn();
    const unsubscribe = session.subscribe(listener);

    session.set({ subject: 'a@b.com', roles: ['ROLE_USER'] });
    session.clear();
    unsubscribe();
    session.set({ subject: 'c@d.com', roles: ['ROLE_USER'] });

    expect(listener).toHaveBeenCalledTimes(2); // not 3 - unsubscribed before the last
  });

  it('returns returnTo once, then forgets it', () => {
    session.setReturnTo('/bookings/7');

    expect(session.takeReturnTo()).toBe('/bookings/7');
    // Consumed - otherwise a later sign-in would bounce somewhere unexpected.
    expect(session.takeReturnTo()).toBeNull();
  });
});
