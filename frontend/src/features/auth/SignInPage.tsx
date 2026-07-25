import { Link, useNavigate } from 'react-router-dom';
import { AuthLayout } from './AuthLayout';
import { SignInForm } from './SignInForm';
import { session } from '../../lib/session';

/**
 * Sign in (FRONTEND_MODULE.md §5, screen 1).
 *
 * <p>Just a page frame around {@link SignInForm}: on success it sends the user
 * back where they were interrupted, if anything interrupted them. The form
 * carries the fields, the "keep me signed in" and "forgot password" links, and
 * the identical-401 handling that keeps accounts un-enumerable.
 */
export function SignInPage() {
  const navigate = useNavigate();

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
      <SignInForm
        onSignedIn={() => {
          // Back where they were interrupted - an expiry mid-journey should not
          // cost someone their place.
          const returnTo = session.takeReturnTo();
          navigate(returnTo ?? '/', { replace: true });
        }}
      />
    </AuthLayout>
  );
}
