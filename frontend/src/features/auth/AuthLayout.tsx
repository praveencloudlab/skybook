import type { ReactNode } from 'react';

/**
 * The frame both auth screens sit in (FRONTEND_MODULE.md §10.2).
 *
 * <p>Two panels: the form on the left, and a brand panel on the right that sets
 * the airline tone before a passenger has seen a single flight. The panel is
 * hidden below `lg` rather than stacked - on a phone it would just push the form
 * below the fold, which is the opposite of helpful.
 */
export function AuthLayout({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="grid min-h-[calc(100vh-3.5rem)] lg:grid-cols-2">
      <div className="flex items-center justify-center px-6 py-12">
        <div className="w-full max-w-sm">
          <h1 className="text-2xl font-semibold tracking-tight text-slate-900">{title}</h1>
          <p className="mt-1.5 text-sm text-slate-600">{subtitle}</p>
          <div className="mt-8">{children}</div>
        </div>
      </div>

      <aside
        className="relative hidden overflow-hidden bg-brand-900 lg:block"
        aria-hidden="true"
      >
        {/* A suggestion of altitude: layered gradients rather than a stock photo,
            so there is no asset to ship, nothing to load, and it cannot look
            dated the way a hero image does. */}
        <div className="absolute inset-0 bg-gradient-to-br from-brand-800 via-brand-900 to-slate-950" />
        <div className="absolute -top-24 -right-24 h-96 w-96 rounded-full bg-brand-500/20 blur-3xl" />
        <div className="absolute bottom-0 left-0 h-80 w-80 rounded-full bg-accent-500/10 blur-3xl" />

        {/* Flight paths. */}
        <svg className="absolute inset-0 h-full w-full" viewBox="0 0 400 600" fill="none">
          <path
            d="M-20 480 C 120 420, 200 300, 420 140"
            stroke="white"
            strokeOpacity="0.18"
            strokeWidth="1.5"
            strokeDasharray="5 7"
          />
          <path
            d="M-20 560 C 160 520, 260 420, 420 300"
            stroke="white"
            strokeOpacity="0.1"
            strokeWidth="1.5"
            strokeDasharray="5 7"
          />
          <circle cx="300" cy="238" r="3.5" fill="white" fillOpacity="0.6" />
        </svg>

        <div className="relative flex h-full flex-col justify-end p-12 text-white">
          <p className="text-3xl leading-tight font-semibold tracking-tight">
            Thirty routes.
            <br />
            A year of departures.
          </p>
          <p className="mt-3 max-w-sm text-sm text-white/60">
            Search real schedules, pick your seat from the actual cabin, and carry a boarding pass
            you can scan.
          </p>
        </div>
      </aside>
    </div>
  );
}
