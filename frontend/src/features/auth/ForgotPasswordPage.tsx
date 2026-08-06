import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { authApi } from '../../api/auth';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/Alert';
import { Field } from '../../components/Field';
import { AuthLayout } from './AuthLayout';
import { ApiError } from '../../lib/errors';

/**
 * "Forgot password" (FRONTEND_MODULE.md §5).
 *
 * <p>The confirmation is deliberately the same whether or not the email is
 * registered: the server never reveals which addresses have accounts (no
 * enumeration), and the UI must not undo that. So a success here means only
 * "if that address exists, a link is on its way" - never "that account exists".
 */
export function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await authApi.forgotPassword(email);
      setSent(true);
    } catch (cause) {
      // Only a genuine transport failure lands here - the server returns 202
      // whether or not the account exists.
      setError(cause instanceof ApiError ? cause : null);
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthLayout
      title="Reset your password"
      subtitle={
        <>
          Remembered it?{' '}
          <Link to="/sign-in" className="inline-flex min-h-11 items-center font-medium text-brand-700 hover:underline sm:min-h-0">
            Back to log in
          </Link>
        </>
      }
    >
      {sent ? (
        <div className="space-y-4">
          <div className="rounded-xl border border-brand-200 bg-brand-50 px-4 py-3 text-sm text-brand-900">
            If an account exists for <span className="font-semibold">{email}</span>, we've sent a
            link to reset your password. It's valid for 30 minutes.
          </div>
          <p className="text-sm text-slate-600">
            Didn't get it? Check your spam folder, or{' '}
            <button
              type="button"
              onClick={() => setSent(false)}
              className="inline-flex min-h-11 items-center font-medium text-brand-700 hover:underline sm:min-h-0"
            >
              try another address
            </button>
            .
          </p>
        </div>
      ) : (
        <form onSubmit={handleSubmit} noValidate className="space-y-5">
          <ErrorAlert error={error} />
          <p className="text-sm text-slate-600">
            Enter the email you signed up with and we'll send you a link to set a new password.
          </p>
          <Field
            label="Email"
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            autoComplete="email"
            required
          />
          <Button type="submit" busy={busy} className="w-full">
            Send reset link
          </Button>
        </form>
      )}
    </AuthLayout>
  );
}
