import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, setUnauthenticatedHandler } from './client';
import { ApiError } from '../lib/errors';
import { session } from '../lib/session';

/**
 * The API boundary decides the whole app's auth and error behaviour, so it is
 * tested directly rather than through a screen.
 */

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

describe('api client', () => {
  beforeEach(() => {
    session.clear();
    setUnauthenticatedHandler(() => {});
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('sends cookies and never builds an Authorization header', async () => {
    const fetchMock = vi.fn<typeof fetch>(async () => jsonResponse(200, {}));
    vi.stubGlobal('fetch', fetchMock);

    await api.get('/api/bookings');

    const init = fetchMock.mock.calls[0][1];
    // The credential is an httpOnly cookie the browser attaches itself (§10.1).
    // If an Authorization header ever appears here again, it means someone has
    // reintroduced a JS-readable token.
    expect(init?.credentials).toBe('same-origin');
    expect((init?.headers as Record<string, string>).Authorization).toBeUndefined();
  });

  it('requests same-origin paths so the proxy (and the cookie) apply', async () => {
    const fetchMock = vi.fn<typeof fetch>(async () => jsonResponse(200, {}));
    vi.stubGlobal('fetch', fetchMock);

    await api.get('/api/flights');

    // Relative, not http://localhost:8080 - cross-origin would mean the
    // SameSite=Lax cookie is simply not sent.
    expect(String(fetchMock.mock.calls[0][0])).toBe('/api/flights');
  });

  it('reads a raw (non-JSON) body without trying to parse it', async () => {
    // POST /api/auth/login still returns a bare token as text/plain.
    const rawToken = 'header.payload.signature';
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        new Response(rawToken, { status: 200, headers: { 'content-type': 'text/plain' } }),
      ),
    );

    await expect(api.post<string>('/api/auth/login', {})).resolves.toBe(rawToken);
  });

  it('parses a JSON body when the server says it is JSON', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(200, { id: 7, pnr: 'SB1234' })));

    await expect(api.get<{ id: number }>('/api/bookings/7')).resolves.toEqual({
      id: 7,
      pnr: 'SB1234',
    });
  });

  it('clears cached identity and notifies once on a 401', async () => {
    session.set({ subject: 'a@b.com', roles: ['ROLE_USER'] });
    const onUnauthenticated = vi.fn();
    setUnauthenticatedHandler(onUnauthenticated);
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(401, { message: 'Authentication required' })));

    await expect(api.get('/api/bookings')).rejects.toBeInstanceOf(ApiError);

    expect(session.current()).toBeNull();
    expect(onUnauthenticated).toHaveBeenCalledTimes(1);
  });

  it('maps status codes to actionable kinds, not raw numbers', async () => {
    const cases: Array<[number, string]> = [
      [400, 'validation'],
      [403, 'forbidden'],
      [409, 'conflict'],
      [422, 'declined'],
      [429, 'rateLimited'],
      [502, 'unavailable'],
    ];

    for (const [status, expected] of cases) {
      vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(status, { message: 'x' })));
      await expect(api.get('/api/anything')).rejects.toMatchObject({ kind: expected, status });
    }
  });

  it('reports a failure to reach the gateway as a network error, not a 500', async () => {
    // fetch rejects only when no HTTP response happened at all.
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));

    await expect(api.get('/api/flights')).rejects.toMatchObject({
      kind: 'network',
      status: 0,
    });
  });
});
