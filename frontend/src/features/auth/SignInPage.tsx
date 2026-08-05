import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { AuthLayout } from './AuthLayout';
import { SignInForm } from './SignInForm';
import { Alert } from '../../components/Alert';
import { session } from '../../lib/session';

/**
 * The SSO failure copy (SSO_MODULE.md §7). Four codes, deliberately: the
 * user's own cancellation and the unverified-email rejection are actionable
 * and get their own words; every technical failure collapses into one
 * generic bucket, because a passenger can act on none of the distinctions
 * and enumerating internals in a URL parameter is surface for no gain.
 */
const SSO_ERRORS: Record<string, string> = {
  sso_cancelled: 'Google sign-in was cancelled.',
  sso_email_unverified:
    "Your Google email address isn't verified, so we can't use it to sign you in.",
  sso_unavailable: "Google sign-in isn't available right now.",
  sso_failed: "Google sign-in didn't complete. Please try again or use your password.",
};

/**
 * Sign in (FRONTEND_MODULE.md §5, screen 1).
 *
 * <p>Just a page frame around {@link SignInForm}: on success it sends the user
 * back where they were interrupted, if anything interrupted them. The form
 * carries the fields, the "keep me signed in" and "forgot password" links, and
 * the identical-401 handling that keeps accounts un-enumerable.
 *
 * <p>Also where a failed Google sign-in lands (the server redirects to
 * {@code /login?error=...}), so the page-level alert above the form is the
 * OAuth flow's error surface.
 */
export function SignInPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const errorCode = searchParams.get('error');
  const ssoError = errorCode ? SSO_ERRORS[errorCode] : undefined;

  return (
    <AuthLayout
      title="Log in"
      subtitle={
        <>
          New here?{' '}
          <Link to="/register" className="font-medium text-brand-700 hover:underline">
            Create an account
          </Link>
        </>
      }
    >
      {ssoError && <div className="mb-5"><Alert>{ssoError}</Alert></div>}

      <SignInForm
        showSso
        onSignedIn={() => {
          // Back where they were interrupted - an expiry mid-journey should not
          // cost someone their place. Otherwise admins land in the console
          // (their whole job is there), passengers on the home page.
          const returnTo = session.takeReturnTo();
          if (returnTo) {
            navigate(returnTo, { replace: true });
            return;
          }
          const isAdmin = session.current()?.roles.includes('ROLE_ADMIN') ?? false;
          navigate(isAdmin ? '/admin' : '/', { replace: true });
        }}
      />
    </AuthLayout>
  );
}
