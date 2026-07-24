import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { authApi } from '../../api/auth';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/Alert';
import { Field } from '../../components/Field';
import { ApiError } from '../../lib/errors';
import { session } from '../../lib/session';

/**
 * Sign in (FRONTEND_MODULE.md §5, screen 1).
 *
 * <p>Note what is deliberately NOT here: any password-complexity checking. The
 * server applies the policy at registration only, so accounts created under an
 * older policy can still sign in (SECURITY_HARDENING_MODULE.md §6). Mirroring
 * the register screen's checklist here would lock exactly those people out of
 * their own accounts.
 */
export function SignInPage() {
  const navigate = useNavigate();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);

    try {
      await authApi.login({ email, password });
      // Back where they were interrupted, if anything interrupted them - an
      // expiry mid-journey should not cost someone their place.
      const returnTo = session.takeReturnTo();
      navigate(returnTo ?? '/', { replace: true });
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="mx-auto max-w-md px-6 py-12">
      <h1 className="text-2xl font-semibold tracking-tight text-slate-900">Sign in</h1>
      <p className="mt-1 text-sm text-slate-600">
        New here?{' '}
        <Link to="/register" className="font-medium text-brand-700 hover:underline">
          Create an account
        </Link>
      </p>

      <form onSubmit={handleSubmit} noValidate className="mt-8 space-y-5">
        {/*
          The server returns an identical 401 for an unknown email and a wrong
          password, on purpose - telling them apart would let anyone enumerate
          who has an account. The UI must not undo that by guessing which it was.
        */}
        <ErrorAlert error={error} />

        <Field
          label="Email"
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          autoComplete="email"
          required
        />

        <Field
          label="Password"
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="current-password"
          required
        />

        <Button type="submit" busy={busy} className="w-full">
          Sign in
        </Button>
      </form>
    </main>
  );
}
