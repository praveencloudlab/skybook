import { useEffect, useState } from 'react';
import { authApi } from '../../api/auth';
import { session } from '../../lib/session';

/**
 * "Continue with Google" (SSO_MODULE.md §3.2, decision D3).
 *
 * <p>Self-deciding: it asks the server which providers this environment offers
 * and renders nothing when Google isn't one of them - so the component can be
 * dropped onto the standalone auth pages with zero wiring, and an environment
 * without a Google client simply never shows it.
 *
 * <p>Deliberately NOT rendered in the mid-booking inline gate: this is a
 * full-page navigation, and the booking funnel's state is in-memory - the
 * redirect would destroy it. The standalone pages have nothing to lose.
 *
 * <p>It is an <em>anchor-shaped navigation, not a fetch</em>: the whole OAuth
 * dance is server-driven redirects, and the browser must follow them. The
 * returnTo is consumed at CLICK time from the same place password sign-in
 * takes it, so both roads lead back to wherever the user was interrupted.
 */
export function GoogleSignInButton({ remember = false }: { remember?: boolean }) {
  const [offered, setOffered] = useState(false);

  useEffect(() => {
    let mounted = true;
    authApi.ssoProviders().then((providers) => {
      if (mounted) {
        setOffered(providers.includes('google'));
      }
    });
    return () => {
      mounted = false;
    };
  }, []);

  if (!offered) {
    return null;
  }

  function startGoogleSignIn() {
    const returnTo = session.takeReturnTo() ?? '/';
    const query = new URLSearchParams({ remember: String(remember), returnTo });
    // A navigation, not a fetch: the server answers with a 302 to Google, and
    // the browser must follow it out of the SPA entirely.
    window.location.assign(`/api/auth/oauth2/authorization/google?${query}`);
  }

  return (
    <div className="space-y-5">
      <div className="flex items-center gap-3" aria-hidden="true">
        <span className="h-px flex-1 bg-slate-200" />
        <span className="text-xs uppercase tracking-wide text-slate-400">or</span>
        <span className="h-px flex-1 bg-slate-200" />
      </div>

      <button
        type="button"
        onClick={startGoogleSignIn}
        className="flex w-full items-center justify-center gap-3 rounded-lg border border-slate-300 bg-white px-4 py-2.5 text-sm font-medium text-slate-700 shadow-sm transition hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-brand-500/40"
      >
        {/* Google's "G", inline so the page stays self-contained. */}
        <svg width="18" height="18" viewBox="0 0 48 48" aria-hidden="true">
          <path
            fill="#EA4335"
            d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"
          />
          <path
            fill="#4285F4"
            d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"
          />
          <path
            fill="#FBBC05"
            d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"
          />
          <path
            fill="#34A853"
            d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"
          />
        </svg>
        Continue with Google
      </button>
    </div>
  );
}
