import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { GoogleSignInButton } from './GoogleSignInButton';
import { SignInPage } from './SignInPage';
import { authApi } from '../../api/auth';
import { session } from '../../lib/session';

vi.mock('../../api/auth', () => ({
  authApi: {
    ssoProviders: vi.fn(),
  },
}));

const ssoProviders = vi.mocked(authApi.ssoProviders);

describe('Continue with Google (SSO_MODULE.md §3.2/§7)', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    sessionStorage.clear();
  });

  it('renders only when the server announces the provider', async () => {
    // Runtime discovery (decision D4): one promoted image, per-environment
    // behaviour. The button's existence IS the environment's answer.
    ssoProviders.mockResolvedValue(['google']);

    render(<GoogleSignInButton />);

    expect(await screen.findByRole('button', { name: /continue with google/i })).toBeDefined();
  });

  it('renders nothing in an environment with no providers', async () => {
    ssoProviders.mockResolvedValue([]);

    render(<GoogleSignInButton />);

    await waitFor(() => expect(ssoProviders).toHaveBeenCalled());
    expect(screen.queryByRole('button', { name: /continue with google/i })).toBeNull();
  });

  it('navigates with the remember choice and the consumed returnTo', async () => {
    ssoProviders.mockResolvedValue(['google']);
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    session.setReturnTo('/trips');

    render(<GoogleSignInButton remember />);
    fireEvent.click(await screen.findByRole('button', { name: /continue with google/i }));

    expect(assign).toHaveBeenCalledWith(
      '/api/auth/oauth2/authorization/google?remember=true&returnTo=%2Ftrips',
    );
    // Consumed, exactly like the password path: a stale returnTo must not
    // ambush the NEXT unrelated sign-in.
    expect(session.takeReturnTo()).toBeNull();
  });

  it('defaults to the root when nothing interrupted the user', async () => {
    ssoProviders.mockResolvedValue(['google']);
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });

    render(<GoogleSignInButton />);
    fireEvent.click(await screen.findByRole('button', { name: /continue with google/i }));

    expect(assign).toHaveBeenCalledWith(
      '/api/auth/oauth2/authorization/google?remember=false&returnTo=%2F',
    );
  });
});

describe('SSO failure copy on the sign-in page (SSO_MODULE.md §7)', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  function renderWithError(code: string) {
    ssoProviders.mockResolvedValue([]);
    return render(
      <MemoryRouter initialEntries={[`/login?error=${code}`]}>
        <SignInPage />
      </MemoryRouter>,
    );
  }

  it.each([
    ['sso_cancelled', 'Google sign-in was cancelled.'],
    ['sso_email_unverified', "Your Google email address isn't verified, so we can't use it to sign you in."],
    ['sso_unavailable', "Google sign-in isn't available right now."],
    ['sso_failed', "Google sign-in didn't complete. Please try again or use your password."],
  ])('renders the %s copy', async (code, copy) => {
    renderWithError(code);
    expect(screen.getByText(copy)).toBeDefined();
  });

  it('renders nothing for an unknown error code', () => {
    renderWithError('not_a_real_code');
    expect(screen.queryByText(/google sign-in/i)).toBeNull();
  });
});
