import { Link, useNavigate } from 'react-router-dom';
import { authApi } from '../api/auth';
import { useSession } from '../features/auth/useSession';

/**
 * Site footer (FRONTEND_MODULE.md Module 2).
 *
 * <p>The quiet credibility marker a real carrier's site always has: a columned
 * footer with the brand, a few honest link groups, and the fine print. The
 * account column is session-aware - a signed-in visitor sees who they are and a
 * way out, not "log in / create account".
 */
export function SiteFooter() {
  const { signedIn, subject, isAdmin } = useSession();
  const navigate = useNavigate();

  async function signOut() {
    await authApi.logout();
    navigate('/', { replace: true });
  }

  return (
    <footer className="mt-16 border-t border-slate-200 bg-white">
      <div className="mx-auto grid max-w-6xl gap-8 px-4 sm:px-6 py-12 sm:grid-cols-2 lg:grid-cols-4">
        <div className="sm:col-span-2 lg:col-span-2">
          <Link to="/" className="inline-flex min-h-11 items-center gap-2.5">
            <span className="grid h-8 w-8 place-items-center rounded-lg bg-brand-600">
              <svg viewBox="0 0 24 24" className="h-4 w-4 fill-white" aria-hidden="true">
                <path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z" />
              </svg>
            </span>
            <span className="text-base font-semibold tracking-tight text-slate-900">SkyBook</span>
          </Link>
          <p className="mt-3 max-w-xs text-sm text-slate-500">
            A working demonstration airline: real schedules, real seat maps, and a boarding pass you
            can actually scan.
          </p>
        </div>

        {/* Admins get operations links here, not the passenger booking menu. */}
        {isAdmin ? (
          <div>
            <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">Operations</h3>
            <ul className="mt-1 sm:mt-3 sm:space-y-2">
              <li>
                <Link to="/admin" className="inline-flex min-h-11 items-center text-sm text-slate-600 transition hover:text-brand-700 lg:min-h-0">
                  Admin console
                </Link>
              </li>
            </ul>
          </div>
        ) : (
          <div>
            <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">Book</h3>
            <ul className="mt-1 sm:mt-3 sm:space-y-2">
              <li>
                <Link to="/search" className="inline-flex min-h-11 items-center text-sm text-slate-600 transition hover:text-brand-700 lg:min-h-0">
                  Search flights
                </Link>
              </li>
              <li>
                <Link to="/bookings" className="inline-flex min-h-11 items-center text-sm text-slate-600 transition hover:text-brand-700 lg:min-h-0">
                  My trips
                </Link>
              </li>
            </ul>
          </div>
        )}

        {/* Account - reflects whether someone is signed in. */}
        <div>
          <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">Account</h3>
          {signedIn ? (
            <div className="mt-1 sm:mt-3 sm:space-y-2">
              <p className="max-w-[22ch] truncate text-sm text-slate-500" title={subject ?? ''}>
                Signed in as <span className="font-medium text-slate-700">{subject}</span>
              </p>
              <button
                type="button"
                onClick={signOut}
                className="text-sm font-medium text-brand-700 transition hover:underline"
              >
                Sign out
              </button>
            </div>
          ) : (
            <ul className="mt-1 sm:mt-3 sm:space-y-2">
              <li>
                <Link to="/sign-in" className="inline-flex min-h-11 items-center text-sm text-slate-600 transition hover:text-brand-700 lg:min-h-0">
                  Log in
                </Link>
              </li>
              <li>
                <Link to="/register" className="inline-flex min-h-11 items-center text-sm text-slate-600 transition hover:text-brand-700 lg:min-h-0">
                  Create account
                </Link>
              </li>
              <li>
                <Link
                  to="/forgot-password"
                  className="inline-flex min-h-11 items-center text-sm text-slate-600 transition hover:text-brand-700 lg:min-h-0"
                >
                  Reset password
                </Link>
              </li>
            </ul>
          )}
        </div>
      </div>

      <div className="border-t border-slate-100">
        <div className="mx-auto flex max-w-6xl flex-col items-center justify-between gap-2 px-6 py-5 text-xs text-slate-400 sm:flex-row">
          <p>© {new Date().getFullYear()} SkyBook. A portfolio project — not a real airline.</p>
          <p className="tabular">29 airports · every pair served daily · a year of departures</p>
        </div>
      </div>
    </footer>
  );
}
