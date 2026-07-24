import { API_BASE_URL } from '../lib/config';
import { ApiError, kindForStatus, type ApiErrorBody } from '../lib/errors';
import { session } from '../lib/session';

/**
 * The one place `fetch` is called (FRONTEND_MODULE.md §2).
 *
 * Everything that must be true of every request lives here exactly once: the
 * bearer header, the 401 rule, error mapping. Screens calling `fetch` directly
 * is how a codebase ends up with five different ideas of what a 401 means.
 */

/** Where to send someone whose session just ended. Set once by the router. */
let onUnauthenticated: (() => void) | null = null;

export function setUnauthenticatedHandler(handler: () => void): void {
  onUnauthenticated = handler;
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  /** Extra headers, e.g. Idempotency-Key on payment creation. */
  headers?: Record<string, string>;
  /** Send without the bearer token (register and login). */
  anonymous?: boolean;
  signal?: AbortSignal;
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, headers = {}, anonymous = false, signal } = options;

  const finalHeaders: Record<string, string> = { ...headers };
  if (body !== undefined) {
    finalHeaders['Content-Type'] = 'application/json';
  }
  if (!anonymous) {
    const token = session.token();
    if (token) {
      finalHeaders.Authorization = `Bearer ${token}`;
    }
  }

  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      method,
      headers: finalHeaders,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    });
  } catch (cause) {
    // fetch only rejects when the request never got an HTTP response at all -
    // gateway down, DNS, CORS, offline. An HTTP 500 resolves normally.
    if (cause instanceof DOMException && cause.name === 'AbortError') {
      throw cause; // caller cancelled; not a failure to report
    }
    throw new ApiError('network', 0, 'Could not reach SkyBook');
  }

  if (response.ok) {
    return (await readBody(response)) as T;
  }

  // A 401 means the session is over, full stop: there is no refresh token to
  // silently renew with (deferred in the security module), so the only honest
  // response is to clear it and send them to sign in - preserving where they
  // were, so an expiry mid-journey doesn't lose their place.
  if (response.status === 401) {
    session.clear();
    onUnauthenticated?.();
  }

  const errorBody = await readErrorBody(response);
  throw new ApiError(
    kindForStatus(response.status),
    response.status,
    errorBody?.message ?? response.statusText,
    errorBody,
  );
}

/**
 * Read a success body.
 *
 * <b>This is where the login quirk bites.</b> `POST /api/auth/login` returns the
 * JWT as a RAW STRING, not JSON - so `response.json()` throws on the single most
 * important call in the app. It has already caught out a Postman script and the
 * e2e suite. Rather than special-casing the login path (which the next person
 * would not know about), we branch on what the server actually said it sent.
 */
async function readBody(response: Response): Promise<unknown> {
  if (response.status === 204) {
    return undefined;
  }
  const text = await response.text();
  if (text === '') {
    return undefined;
  }
  const contentType = response.headers.get('content-type') ?? '';
  if (!contentType.includes('application/json')) {
    return text;
  }
  try {
    return JSON.parse(text);
  } catch {
    // Content-Type claimed JSON but the body is not - trust the bytes.
    return text;
  }
}

async function readErrorBody(response: Response): Promise<ApiErrorBody | undefined> {
  try {
    const text = await response.text();
    if (text === '') {
      return undefined;
    }
    try {
      return JSON.parse(text) as ApiErrorBody;
    } catch {
      return { message: text };
    }
  } catch {
    return undefined;
  }
}

export const api = {
  get: <T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    request<T>(path, { ...options, method: 'GET' }),

  post: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    request<T>(path, { ...options, method: 'POST', body }),

  patch: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    request<T>(path, { ...options, method: 'PATCH', body }),

  put: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    request<T>(path, { ...options, method: 'PUT', body }),

  delete: <T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    request<T>(path, { ...options, method: 'DELETE' }),
};
