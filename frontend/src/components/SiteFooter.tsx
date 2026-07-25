import { Link } from 'react-router-dom';

/**
 * Site footer (FRONTEND_MODULE.md Module 2).
 *
 * <p>The quiet credibility marker a real carrier's site always has: a columned
 * footer with the brand, a few honest link groups, and the fine print. Links
 * point at pages that exist (or are clearly informational) - nothing here
 * pretends to a feature the app doesn't have.
 */
const GROUPS: Array<{ heading: string; links: Array<{ label: string; to: string }> }> = [
  {
    heading: 'Book',
    links: [
      { label: 'Search flights', to: '/search' },
      { label: 'My trips', to: '/bookings' },
      { label: 'Check in', to: '/bookings' },
    ],
  },
  {
    heading: 'Account',
    links: [
      { label: 'Log in', to: '/sign-in' },
      { label: 'Create account', to: '/register' },
      { label: 'Reset password', to: '/forgot-password' },
    ],
  },
];

export function SiteFooter() {
  return (
    <footer className="mt-16 border-t border-slate-200 bg-white">
      <div className="mx-auto grid max-w-6xl gap-8 px-6 py-12 sm:grid-cols-2 lg:grid-cols-4">
        <div className="sm:col-span-2 lg:col-span-2">
          <Link to="/" className="flex items-center gap-2.5">
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

        {GROUPS.map((group) => (
          <div key={group.heading}>
            <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
              {group.heading}
            </h3>
            <ul className="mt-3 space-y-2">
              {group.links.map((link) => (
                <li key={link.label}>
                  <Link to={link.to} className="text-sm text-slate-600 transition hover:text-brand-700">
                    {link.label}
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>

      <div className="border-t border-slate-100">
        <div className="mx-auto flex max-w-6xl flex-col items-center justify-between gap-2 px-6 py-5 text-xs text-slate-400 sm:flex-row">
          <p>© {new Date().getFullYear()} SkyBook. A portfolio project — not a real airline.</p>
          <p className="tabular">30 routes · 16 airports · a year of departures</p>
        </div>
      </div>
    </footer>
  );
}
