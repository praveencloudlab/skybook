import { api } from './client';
import { session } from '../lib/session';

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
    return api.post<string>('/api/auth/register', request, { anonymous: true });
  },

  /**
   * Sign in and store the session.
   *
   * The response body is the RAW JWT, not JSON (§1.2) - handled centrally in the
   * client's body reader, which is why nothing special is needed here.
   */
  async login(request: LoginRequest): Promise<string> {
    const token = await api.post<string>('/api/auth/login', request, { anonymous: true });
    const trimmed = typeof token === 'string' ? token.trim() : '';
    if (!trimmed.includes('.')) {
      // Fail loudly rather than storing junk and 401-ing on every later call.
      throw new Error('Login did not return a token');
    }
    session.setToken(trimmed);
    return trimmed;
  },

  logout(): void {
    // Purely client-side: there is no server-side revocation to call (deferred,
    // security module §14). The token stays technically valid until it expires -
    // which is precisely why it lives in sessionStorage and not on disk.
    session.clear();
  },
};
