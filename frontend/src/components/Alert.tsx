import type { ReactNode } from 'react';
import { ApiError, userMessage } from '../lib/errors';

/**
 * A message about something that went wrong (or nearly did).
 *
 * <p>Tone follows FRONTEND_MODULE.md §6: a declined card is not a system fault
 * and must not be styled as one, and a 502 is our problem rather than the
 * user's. So a declined payment is amber and reads as an outcome, while an
 * unreachable service is red and takes the blame.
 */
type Tone = 'error' | 'warning' | 'info';

const TONES: Record<Tone, string> = {
  error: 'border-red-200 bg-red-50 text-red-800',
  warning: 'border-amber-200 bg-amber-50 text-amber-900',
  info: 'border-brand-200 bg-brand-50 text-brand-900',
};

export function Alert({ tone = 'error', children }: { tone?: Tone; children: ReactNode }) {
  return (
    <div role="alert" className={`rounded border px-3 py-2 text-sm ${TONES[tone]}`}>
      {children}
    </div>
  );
}

/** Renders an ApiError with the right words and the right tone for its kind. */
export function ErrorAlert({ error }: { error: ApiError | null }) {
  if (!error) {
    return null;
  }
  // A declined card is an outcome, not a failure - amber, not red.
  const tone: Tone = error.kind === 'declined' ? 'warning' : 'error';
  return <Alert tone={tone}>{userMessage(error)}</Alert>;
}
