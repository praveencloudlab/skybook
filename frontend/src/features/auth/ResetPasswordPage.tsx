import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { authApi, PASSWORD_RULES, passwordPolicyMet } from '../../api/auth';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/Alert';
import { Field } from '../../components/Field';
import { AuthLayout } from './AuthLayout';
import { ApiError } from '../../lib/errors';

/**
 * Set a new password from an emailed reset link (FRONTEND_MODULE.md §5).
 *
 * <p>The token rides in the URL query, exactly as the email link built it. The
 * new password must clear the same complexity policy registration does - a reset
 * sets a brand-new password, so the checklist mirrors the register screen. An
 * unknown, spent, or expired token comes back as a generic 400 the server never
 * disambiguates; we surface it as "this link is no longer valid".
 */
export function ResetPasswordPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';

  const [password, setPassword] = useState('');
  const [touched, setTouched] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  const policyMet = passwordPolicyMet(password);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setTouched(true);
    setError(null);
    if (!policyMet) {
      return;
    }
    setBusy(true);
    try {
      await authApi.resetPassword(token, password);
      setDone(true);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
    } finally {
      setBusy(false);
    }
  }

  // No token at all - the link was mistyped or truncated. Nothing to submit.
  if (!token) {
    return (
      <AuthLayout title="Reset your password" subtitle="This link looks incomplete.">
        <div className="space-y-4">
          <div className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
            This reset link is missing its token. Please request a new one.
          </div>
          <Link to="/forgot-password" className="text-sm font-medium text-brand-700 hover:underline">
            Request a new link
          </Link>
        </div>
      </AuthLayout>
    );
  }

  if (done) {
    return (
      <AuthLayout title="Password updated" subtitle="You're all set.">
        <div className="space-y-4">
          <div className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900">
            Your password has been changed. You can now log in with it.
          </div>
          <Button className="w-full" onClick={() => navigate('/sign-in', { replace: true })}>
            Go to log in
          </Button>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout
      title="Choose a new password"
      subtitle={
        <>
          Changed your mind?{' '}
          <Link to="/sign-in" className="font-medium text-brand-700 hover:underline">
            Back to log in
          </Link>
        </>
      }
    >
      <form onSubmit={handleSubmit} noValidate className="space-y-5">
        <ErrorAlert error={error} />
        {error && error.status === 400 ? (
          <p className="text-sm text-slate-600">
            <Link to="/forgot-password" className="font-medium text-brand-700 hover:underline">
              Request a fresh link
            </Link>{' '}
            and try again.
          </p>
        ) : null}

        <Field
          label="New password"
          type="password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          autoComplete="new-password"
          required
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
          Set new password
        </Button>
      </form>
    </AuthLayout>
  );
}
