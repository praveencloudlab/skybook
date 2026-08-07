import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { authApi } from '../../api/auth';
import { Button } from '../../components/Button';
import { Alert, ErrorAlert } from '../../components/Alert';
import { Field } from '../../components/Field';
import { GoogleSignInButton } from './GoogleSignInButton';
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
 *
 * <p>{@code showSso} is the standalone page's flag (SSO_MODULE.md D3): the
 * Google button is a full-page navigation, and the inline gate exists
 * precisely where navigation would destroy the in-memory booking funnel - so
 * the gate never passes it. Living inside the form lets the button share the
 * form's own "keep me signed in" choice.
 */
export function SignInForm({ onSignedIn, showSso = false }: { onSignedIn: () => void; showSso?: boolean }) {
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

        A 401 HERE means the credentials were wrong - the generic mapping's
        "session expired" wording is for 401s on authenticated calls, and on a
        login form it reads as a bug.
      */}
      {error?.kind === 'unauthenticated' ? (
        <Alert>Incorrect email or password. Please try again.</Alert>
      ) : (
        <ErrorAlert error={error} />
      )}

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
        <Link to="/forgot-password" className="inline-flex min-h-11 items-center text-sm font-medium text-brand-700 hover:underline lg:min-h-0">
          Forgot password?
        </Link>
      </div>

      <Button type="submit" busy={busy} className="w-full">
        Log in
      </Button>

      {showSso && <GoogleSignInButton remember={remember} />}
    </form>
  );
}
