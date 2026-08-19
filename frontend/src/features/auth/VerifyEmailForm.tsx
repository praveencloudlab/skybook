import { useEffect, useState, type FormEvent } from 'react';
import { authApi } from '../../api/auth';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/Alert';
import { Field } from '../../components/Field';
import { ApiError } from '../../lib/errors';

/**
 * The 6-digit email-verification step, shared by two hosts: RegisterPage
 * (right after creating the account) and SignInForm (when a sign-in bounces
 * off an unverified account). Both need the same code entry, the same
 * resend-with-cooldown, and the same error surface - only what happens after
 * a successful redemption differs, which is {@code onVerified}'s job.
 */
export function VerifyEmailForm({
  email,
  onVerified,
  onChangeEmail,
  initialCooldown = 60,
}: {
  email: string;
  /** Runs after the server accepts the code - typically sign in + navigate. */
  onVerified: () => Promise<void>;
  /**
   * "Wrong email?" escape hatch: the host takes the user back to where the
   * address can be corrected (the registration form, with everything still
   * filled in). A typo'd email is otherwise a dead end - the code went to an
   * inbox that isn't theirs, and re-registering the unverified address is
   * exactly what the server permits.
   */
  onChangeEmail?: () => void;
  /**
   * Seconds before "Send a new code" unlocks. 60 when a code was JUST sent
   * (registration); 0 when the outstanding code may be long stale (a sign-in
   * bounced days later) and an immediate resend is the likely need.
   */
  initialCooldown?: number;
}) {
  const [otp, setOtp] = useState('');
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const [cooldown, setCooldown] = useState(initialCooldown);
  const [resent, setResent] = useState(false);

  useEffect(() => {
    if (cooldown <= 0) return;
    const timer = setInterval(() => setCooldown((s) => s - 1), 1000);
    return () => clearInterval(timer);
  }, [cooldown]);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (otp.length !== 6) return;
    setError(null);
    setBusy(true);
    try {
      await authApi.verifyEmail(email, otp);
      await onVerified();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
    } finally {
      setBusy(false);
    }
  }

  async function resend() {
    if (cooldown > 0) return;
    setError(null);
    try {
      await authApi.resendVerification(email);
      setResent(true);
      setOtp('');
      setCooldown(60);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
    }
  }

  return (
    <form onSubmit={handleSubmit} noValidate className="space-y-5">
      <ErrorAlert error={error} />

      <p className="text-sm leading-relaxed text-slate-600">
        We sent a 6-digit code to <span className="font-semibold text-slate-900">{email}</span>.
        Enter it below to activate your account.
      </p>

      <Field
        label="Verification code"
        value={otp}
        onChange={(e) => setOtp(e.target.value.replace(/\D/g, '').slice(0, 6))}
        inputMode="numeric"
        autoComplete="one-time-code"
        maxLength={6}
        placeholder="000000"
        autoFocus
        className="tracking-[0.4em] text-center text-lg font-bold"
      />

      <Button type="submit" busy={busy} disabled={otp.length !== 6} className="w-full">
        Verify email
      </Button>

      <p className="text-center text-sm text-slate-600">
        {resent && cooldown > 50 ? (
          <span className="text-emerald-700">A new code is on its way - check your inbox.</span>
        ) : cooldown > 0 ? (
          <>You can request a new code in {cooldown}s</>
        ) : (
          <button
            type="button"
            onClick={resend}
            className="font-medium text-brand-700 hover:underline"
          >
            Send a new code
          </button>
        )}
      </p>

      {onChangeEmail ? (
        <p className="text-center text-sm text-slate-600">
          Wrong email?{' '}
          <button
            type="button"
            onClick={onChangeEmail}
            className="font-medium text-brand-700 hover:underline"
          >
            Enter a new email
          </button>
        </p>
      ) : null}
    </form>
  );
}
