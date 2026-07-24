import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { authApi, PASSWORD_RULES, passwordPolicyMet } from '../../api/auth';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/Alert';
import { Field } from '../../components/Field';
import { AuthLayout } from './AuthLayout';
import { ApiError, fieldErrors } from '../../lib/errors';

/**
 * Create an account (FRONTEND_MODULE.md §5, screen 1).
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
      // Straight in - making someone type the same credentials again
      // immediately after creating them is friction for no benefit.
      await authApi.login({ email, password });
      navigate('/', { replace: true });
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthLayout
      title="Create your account"
      subtitle={
        <>
          Already have one?{' '}
          <Link to="/sign-in" className="font-medium text-brand-700 hover:underline">
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
      </form>
    </AuthLayout>
  );
}
