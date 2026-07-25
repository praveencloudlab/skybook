import { Link } from 'react-router-dom';

/**
 * A full-page error screen (FRONTEND_MODULE.md Module 18).
 *
 * <p>One component, several presets, so every failure the app can land a whole
 * page on reads the same and stays on-brand: a big status code over a quiet navy
 * motif, a plain-English explanation, and a way out. Tone follows §6 - a 500 is
 * our fault and says so, a 404 is a wrong turn, not an accusation.
 */
export type ErrorCode = '401' | '403' | '404' | '409' | '422' | '500' | '503';

const PRESETS: Record<ErrorCode, { title: string; message: string }> = {
  '401': {
    title: 'Please sign in',
    message: 'You need to be signed in to see this page.',
  },
  '403': {
    title: 'No access',
    message: "You don't have permission to view this. If that seems wrong, sign in with the right account.",
  },
  '404': {
    title: 'Page not found',
    message: 'That page has taken off without us. Check the address, or head back and start again.',
  },
  '409': {
    title: 'That clashed with something',
    message: 'The action conflicted with the current state — refresh and try again.',
  },
  '422': {
    title: "We couldn't process that",
    message: 'Some of the details weren’t accepted. Go back, check the form, and try again.',
  },
  '500': {
    title: 'Something went wrong',
    message: "That one’s on us, not you. Try again in a moment — if it keeps happening, come back later.",
  },
  '503': {
    title: 'Down for maintenance',
    message: 'SkyBook is briefly offline for maintenance. We’ll be back shortly.',
  },
};

export function ErrorPage({
  code,
  title,
  message,
  onRetry,
}: {
  code: ErrorCode;
  title?: string;
  message?: string;
  /** Shown as a "Try again" button when provided (e.g. from an error boundary). */
  onRetry?: () => void;
}) {
  const preset = PRESETS[code];

  return (
    <main className="mx-auto flex min-h-[calc(100vh-3.5rem)] max-w-xl flex-col items-center justify-center px-6 py-16 text-center">
      {/* Navy motif with the code sitting on it. */}
      <div className="relative w-full overflow-hidden rounded-2xl bg-brand-950 px-6 py-12">
        <div className="grid-texture absolute inset-0" />
        <svg className="absolute inset-0 h-full w-full" viewBox="0 0 400 200" fill="none" aria-hidden="true">
          <path d="M-20 150 C 100 110, 240 70, 420 20" stroke="white" strokeOpacity="0.12" strokeWidth="1.5" strokeDasharray="6 9" />
          <circle cx="300" cy="55" r="3.5" fill="white" fillOpacity="0.5" />
        </svg>
        <div className="relative">
          <div className="tabular text-6xl font-semibold tracking-tight text-white sm:text-7xl">{code}</div>
          <div className="mt-3 inline-flex items-center gap-1.5 text-xs font-medium uppercase tracking-[0.2em] text-white/50">
            <svg viewBox="0 0 24 24" className="h-3.5 w-3.5 fill-white/60" aria-hidden="true">
              <path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z" />
            </svg>
            SkyBook
          </div>
        </div>
      </div>

      <h1 className="mt-8 text-2xl font-semibold tracking-tight text-slate-900">{title ?? preset.title}</h1>
      <p className="mt-2 max-w-md text-sm text-slate-600">{message ?? preset.message}</p>

      <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
        {onRetry ? (
          <button
            type="button"
            onClick={onRetry}
            className="inline-flex items-center gap-2 rounded-xl bg-brand-600 px-5 py-2.5 text-sm font-semibold text-white shadow-[var(--shadow-btn)] transition hover:bg-brand-700 hover:-translate-y-0.5"
          >
            Try again
          </button>
        ) : null}
        <Link
          to="/"
          className="inline-flex items-center gap-2 rounded-xl border border-slate-200 bg-white px-5 py-2.5 text-sm font-semibold text-slate-700 shadow-sm transition hover:border-slate-300 hover:bg-slate-50"
        >
          Back to home
        </Link>
        <Link
          to="/search"
          className="inline-flex items-center gap-2 rounded-xl px-5 py-2.5 text-sm font-semibold text-brand-700 transition hover:bg-brand-50"
        >
          Search flights →
        </Link>
      </div>
    </main>
  );
}
