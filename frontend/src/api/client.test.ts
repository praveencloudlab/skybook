import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, setUnauthenticatedHandler } from './client';
import { ApiError } from '../lib/errors';
import { session } from '../lib/session';

/**
 * The API boundary is where the whole app's error and auth behaviour is decided,
 * so it is worth testing directly rather than through a screen.
 *
 * The first case is the one that matters most: login returns a RAW JWT, not
 * JSON, and a client that assumes JSON breaks on the single most important call
 * in the app.
 */

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

describe('api client', () => {
  beforeEach(() => {
    sessionStorage.clear();
    setUnauthenticatedHandler(() => {});
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('reads a raw (non-JSON) body without trying to parse it', async () => {
    // Exactly what POST /api/auth/login sends: a bare token, text/plain.
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

  it('attaches the bearer token, and omits it when anonymous', async () => {
    session.setToken('a.b.c');
    // Typed as `fetch` so the recorded calls keep their real signature -
    // otherwise mock.calls[n][1] is typed as never and the assertions below
    // need casts that would hide a genuine shape change.
    const fetchMock = vi.fn<typeof fetch>(async () => jsonResponse(200, {}));
    vi.stubGlobal('fetch', fetchMock);

    await api.get('/api/bookings');
    await api.post('/api/auth/login', {}, { anonymous: true });

    const authedHeaders = fetchMock.mock.calls[0][1]?.headers as Record<string, string>;
    const anonHeaders = fetchMock.mock.calls[1][1]?.headers as Record<string, string>;
    expect(authedHeaders.Authorization).toBe('Bearer a.b.c');
    expect(anonHeaders.Authorization).toBeUndefined();
  });

  it('clears the session and notifies once on a 401', async () => {
    session.setToken('a.b.c');
    const onUnauthenticated = vi.fn();
    setUnauthenticatedHandler(onUnauthenticated);
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(401, { message: 'Authentication required' })));

    await expect(api.get('/api/bookings')).rejects.toBeInstanceOf(ApiError);

    expect(session.token()).toBeNull();
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
