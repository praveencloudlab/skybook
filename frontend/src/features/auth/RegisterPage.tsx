import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { authApi, PASSWORD_RULES, passwordPolicyMet } from '../../api/auth';
import { session } from '../../lib/session';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/Alert';
import { Field } from '../../components/Field';
import { AuthLayout } from './AuthLayout';
import { GoogleSignInButton } from './GoogleSignInButton';
import { VerifyEmailForm } from './VerifyEmailForm';
import { ApiError, fieldErrors } from '../../lib/errors';

/**
 * Create an account (FRONTEND_MODULE.md §5, screen 1).
 *
 * <p>Two steps: the account form, then the emailed 6-digit code. The account
 * exists after step one but cannot sign in until the code is redeemed - so the
 * page holds the credentials in memory and signs in itself the moment
 * verification lands, keeping "register mid-booking" a single interruption.
 *
 * <p>The password policy is shown live, before submit. The server's 400 is
 * accurate but late: being told "must contain a symbol" only after a round trip,
 * having already typed a password you thought was fine, is a needlessly annoying
 * way to learn the rule. The checklist mirrors the server's rule exactly and is
 * a convenience, never an authority - the server re-validates regardless.
 */
export function RegisterPage() {
  const navigate = useNavigate();

  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [touched, setTouched] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const [step, setStep] = useState<'form' | 'verify'>('form');

  const fields = error ? fieldErrors(error) : {};
  const policyMet = passwordPolicyMet(password);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setTouched(true);
    setError(null);

    if (!policyMet) {
      return; // the checklist below already explains what is missing
    }

    setBusy(true);
    try {
      await authApi.register({ fullName, email, password });
      // The server has mailed a 6-digit code; login is refused until it is
      // redeemed. Keep the credentials in memory so verification flows
      // straight into a session without retyping anything.
      setStep('verify');
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
    } finally {
      setBusy(false);
    }
  }

  if (step === 'verify') {
    return (
      <AuthLayout
        title="Verify your email"
        subtitle="One step left - prove the inbox is yours."
      >
        <VerifyEmailForm
          email={email}
          // A typo'd address is otherwise a dead end. Back to the form with
          // every field still filled (same component, state never unmounted);
          // resubmitting re-registers, which the server permits while the
          // address is unverified - the newest registrant takes it over.
          onChangeEmail={() => setStep('form')}
          onVerified={async () => {
            // Straight in - making someone type the same credentials again
            // immediately after creating them is friction for no benefit.
            await authApi.login({ email, password });
            // Honour the same contract as SignInPage: someone sent here
            // mid-booking (the auth gate's returnTo) resumes their journey -
            // the persisted draft is still waiting at /search, and landing
            // them on '/' instead invited a fresh search that wiped it.
            const returnTo = session.takeReturnTo();
            navigate(returnTo ?? '/', { replace: true });
          }}
        />
      </AuthLayout>
    );
  }

  return (
    <AuthLayout
      title="Create your account"
      subtitle={
        <>
          Already have one?{' '}
          <Link to="/sign-in" className="inline-flex min-h-11 items-center font-medium text-brand-700 hover:underline lg:min-h-0">
            Sign in
          </Link>
        </>
      }
    >
      <form onSubmit={handleSubmit} noValidate className="space-y-5">
        <ErrorAlert error={error} />

        <Field
          label="Full name"
          value={fullName}
          onChange={(e) => setFullName(e.target.value)}
          autoComplete="name"
          required
          error={fields.fullName}
        />

        <Field
          label="Email"
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          autoComplete="email"
          required
          error={fields.email}
        />

        <Field
          label="Password"
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="new-password"
          required
          error={touched && !policyMet ? undefined : fields.password}
          hint={
            <ul className="mt-1 space-y-0.5">
              {PASSWORD_RULES.map((rule) => {
                const met = rule.test(password);
                return (
                  <li
                    key={rule.label}
                    className={met ? 'text-emerald-700' : touched ? 'text-red-600' : 'text-slate-500'}
                  >
                    <span aria-hidden="true">{met ? '✓' : '•'}</span> {rule.label}
                  </li>
                );
              })}
            </ul>
          }
        />

        <Button type="submit" busy={busy} className="w-full">
          Create account
        </Button>

        {/* Registering via Google IS signing in - the server provisions the
            account on first sign-in (SSO_MODULE.md §4.2), so the same button
            serves both pages. No remember checkbox exists here; the safer
            session-scoped default applies. */}
        <GoogleSignInButton />
      </form>
    </AuthLayout>
  );
}
