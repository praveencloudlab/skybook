import { api } from './client';
import { session, type CurrentUser } from '../lib/session';

/**
 * Auth surface (FRONTEND_MODULE.md §4).
 *
 * Mirrors the server's own split: registration enforces the password complexity
 * policy, login does NOT (accounts created under an older policy must still be
 * able to sign in) - see SECURITY_HARDENING_MODULE.md §6.
 */

export interface RegisterRequest {
  fullName: string;
  email: string;
  password: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

/**
 * The registration password policy, mirrored from the server so the UI can say
 * what is wrong BEFORE submitting. The server's 400 is accurate but late, and
 * "your password must contain a symbol" is far kinder up front than after a
 * round trip.
 *
 * This is a convenience, never an authority: the server re-validates.
 */
export const PASSWORD_RULES = [
  { label: 'At least 12 characters', test: (v: string) => v.length >= 12 },
  { label: 'A lower-case letter', test: (v: string) => /[a-z]/.test(v) },
  { label: 'An upper-case letter', test: (v: string) => /[A-Z]/.test(v) },
  { label: 'A digit', test: (v: string) => /\d/.test(v) },
  { label: 'A symbol', test: (v: string) => /[^A-Za-z0-9]/.test(v) },
] as const;

export function passwordPolicyMet(password: string): boolean {
  return PASSWORD_RULES.every((rule) => rule.test(password));
}

export const authApi = {
  /** Returns the server's plain-text confirmation ("User registered successfully"). */
  async register(request: RegisterRequest): Promise<string> {
    return api.post<string>('/api/auth/register', request);
  },

  /**
   * Sign in.
   *
   * The response body still carries the raw JWT (§1.2) - we deliberately ignore
   * it. The credential that matters is the httpOnly cookie the server set on
   * this response, which the browser will attach to subsequent requests without
   * this code ever seeing it. Reading the body token here would re-introduce
   * exactly the exposure the cookie exists to remove.
   *
   * Identity comes from the server afterwards, not from decoding anything.
   */
  async login(request: LoginRequest, remember = false): Promise<CurrentUser> {
    // "Keep me signed in" is a query param, not a body field - it governs only
    // how long the cookie lives, never the credential. When true the server
    // writes a persistent cookie (survives a browser restart, within the token's
    // own lifetime); when false, a session cookie the browser drops on close.
    const query = remember ? '?remember=true' : '';
    await api.post<string>(`/api/auth/login${query}`, request);
    return authApi.me();
  },

  /**
   * Begin a password reset. The server always answers the same way whether or
   * not the email is registered (no enumeration), so there is nothing to branch
   * on here - a resolved promise just means "if that address exists, a link is
   * on its way".
   */
  async forgotPassword(email: string): Promise<void> {
    await api.post<void>('/api/auth/forgot-password', { email });
  },

  /** Redeem a reset token and set a new password. A 400 means the link is dead. */
  async resetPassword(token: string, password: string): Promise<void> {
    await api.post<void>('/api/auth/reset-password', { token, password });
  },

  /**
   * Who the server says we are. Also how a returning visitor is recognised.
   *
   * `silent` skips the global 401->sign-in redirect: the first-load probe must
   * be able to conclude "nobody is signed in" without ejecting an anonymous
   * visitor who is legitimately just browsing public search results.
   */
  async me(silent = false): Promise<CurrentUser> {
    const user = await api.get<CurrentUser>('/api/auth/me', silent ? { silent401: true } : undefined);
    session.set(user);
    return user;
  },

  /**
   * Sign out.
   *
   * Necessarily a server call: the cookie is httpOnly, so the browser cannot
   * delete it itself. The local clear afterwards is only about UI state.
   *
   * This ends the BROWSER session; it does not revoke the token (there is no
   * revocation list yet - security §14), so a copy captured elsewhere would stay
   * valid until it expires.
   */
  async logout(): Promise<void> {
    try {
      await api.post<void>('/api/auth/logout');
    } finally {
      // Clear regardless: if the call failed, the user still asked to sign out,
      // and leaving the UI signed-in would be worse than a stale cookie.
      session.clear();
    }
  },

  /**
   * Establish session state on first load.
   *
   * Returning visitors arrive with a valid cookie and no client state, so the
   * app has to ask. A 401 here is the normal "not signed in" answer, not an
   * error worth surfacing.
   */
  async restore(): Promise<CurrentUser | null> {
    try {
      return await authApi.me(true);
    } catch {
      session.set(null);
      return null;
    }
  },

  /**
   * Which SSO providers this ENVIRONMENT offers (SSO_MODULE.md decision D4).
   *
   * Runtime data, deliberately: the frontend image is built once and promoted
   * through every environment, so provider availability cannot be a build
   * flag. An environment with no Google client returns [] and the button
   * simply never exists there. A failed call means the same thing - a
   * sign-in page must not break because an optional affordance couldn't be
   * enumerated.
   */
  async ssoProviders(): Promise<string[]> {
    try {
      return await api.get<string[]>('/api/auth/sso/providers');
    } catch {
      return [];
    }
  },
};
