import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { authApi } from '../../api/auth';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/Alert';
import { Field } from '../../components/Field';
import { ApiError } from '../../lib/errors';

/**
 * The sign-in form itself, without a page around it.
 *
 * <p>Extracted so it can serve two hosts: the full {@link SignInPage}, and the
 * inline gate shown mid-booking when an anonymous browser reaches a step that
 * needs an account. Both need the same fields, the same "keep me signed in" and
 * "forgot password" affordances, and the same identical-401 handling - the only
 * difference is what happens after success, which is the {@code onSignedIn}
 * callback's job.
 */
export function SignInForm({ onSignedIn }: { onSignedIn: () => void }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [remember, setRemember] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await authApi.login({ email, password }, remember);
      onSignedIn();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} noValidate className="space-y-5">
      {/*
        The server returns an identical 401 for an unknown email and a wrong
        password, on purpose - telling them apart would let anyone enumerate who
        has an account. The UI must not undo that by guessing which it was.
      */}
      <ErrorAlert error={error} />

      <Field
        label="Email"
        type="email"
        value={email}
        onChange={(event) => setEmail(event.target.value)}
        autoComplete="email"
        required
      />

      <Field
        label="Password"
        type="password"
        value={password}
        onChange={(event) => setPassword(event.target.value)}
        autoComplete="current-password"
        required
      />

      <div className="flex items-center justify-between">
        <label className="flex cursor-pointer items-center gap-2 text-sm text-slate-600">
          <input
            type="checkbox"
            checked={remember}
            onChange={(event) => setRemember(event.target.checked)}
            className="h-4 w-4 rounded border-slate-300 text-brand-600 focus:ring-brand-500/40"
          />
          Keep me signed in
        </label>
        <Link to="/forgot-password" className="text-sm font-medium text-brand-700 hover:underline">
          Forgot password?
        </Link>
      </div>

      <Button type="submit" busy={busy} className="w-full">
        Log in
      </Button>
    </form>
  );
}
