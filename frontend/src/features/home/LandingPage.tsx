import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { AirportField } from '../../components/AirportField';
import { addDaysIso, todayIso } from '../../lib/format';

/**
 * Home / landing (FRONTEND_MODULE.md Module 2).
 *
 * <p>The front door: a navy hero with a working search built in, a stats band,
 * curated destinations, and a short "why" row. Everything here deep-links into
 * the real search (`/search?from=&to=&date=`) - the landing never fakes a
 * capability the app doesn't have, it just frames the ones it does.
 */

const DESTINATIONS: Array<{ to: string; city: string; blurb: string; tint: string }> = [
  { to: 'DXB', city: 'Dubai', blurb: 'Gulf gateway', tint: 'from-amber-500 to-orange-700' },
  { to: 'JFK', city: 'New York', blurb: 'The five boroughs', tint: 'from-sky-500 to-brand-800' },
  { to: 'DEL', city: 'Delhi', blurb: 'Old & new capital', tint: 'from-rose-500 to-brand-900' },
  { to: 'CDG', city: 'Paris', blurb: 'A weekend away', tint: 'from-indigo-500 to-brand-900' },
  { to: 'HKG', city: 'Hong Kong', blurb: 'Harbour city', tint: 'from-emerald-500 to-brand-800' },
  { to: 'JNB', city: 'Johannesburg', blurb: 'Gateway to Africa', tint: 'from-teal-500 to-brand-900' },
];

const FEATURES: Array<{ title: string; body: string; icon: string }> = [
  {
    title: 'Real seat maps',
    body: 'Choose from the actual cabin of the aircraft flying — window, aisle, exit row, priced live.',
    icon: 'M4 4h16a1 1 0 0 1 1 1v3H3V5a1 1 0 0 1 1-1zm-1 6h18v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-9zm4 2v5h2v-5H7zm8 0v5h2v-5h-2z',
  },
  {
    title: 'A boarding pass you can scan',
    body: 'Check in online and get a real QR boarding pass, generated the moment your booking is confirmed.',
    icon: 'M3 5h4v4H3V5zm0 10h4v4H3v-4zM15 5h6v4h-6V5zM9 5h2v14H9V5zm4 0h1v6h-1V5zm0 8h1v6h-1v-6zm4-2h4v8h-4v-8z',
  },
  {
    title: 'Fares priced on the spot',
    body: 'Every cabin and fare comes from live availability and pricing — no stale numbers, no surprises at checkout.',
    icon: 'M12 1v2m0 18v2M4.2 4.2l1.4 1.4m12.8 12.8 1.4 1.4M1 12h2m18 0h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10z',
  },
];

function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

function useCountUp(target: number, durationMs = 1100): number {
  const [value, setValue] = useState(prefersReducedMotion() ? target : 0);
  useEffect(() => {
    if (prefersReducedMotion()) {
      setValue(target);
      return;
    }
    let raf = 0;
    const start = performance.now();
    const tick = (now: number) => {
      const t = Math.min((now - start) / durationMs, 1);
      const eased = 1 - Math.pow(1 - t, 3);
      setValue(Math.round(eased * target));
      if (t < 1) {
        raf = requestAnimationFrame(tick);
      }
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [target, durationMs]);
  return value;
}

function Stat({ value, suffix, label }: { value: number; suffix?: string; label: string }) {
  const shown = useCountUp(value);
  return (
    <div>
      <div className="tabular text-3xl font-semibold tracking-tight text-white sm:text-4xl">
        {shown.toLocaleString()}
        {suffix}
      </div>
      <div className="mt-1 text-sm text-white/55">{label}</div>
    </div>
  );
}

export function LandingPage() {
  const navigate = useNavigate();
  const [origin, setOrigin] = useState('LHR');
  const [destination, setDestination] = useState('DXB');
  const [date, setDate] = useState(addDaysIso(todayIso(), 1));
  const sameAirport = origin === destination;

  function search(event: FormEvent) {
    event.preventDefault();
    if (sameAirport) {
      return;
    }
    navigate(`/search?from=${origin}&to=${destination}&date=${date}`);
  }

  return (
    <>
      {/* Hero */}
      <section className="relative overflow-hidden bg-brand-950">
        <div className="grid-texture absolute inset-0" />
        <div className="absolute inset-0 bg-gradient-to-b from-brand-900/50 via-brand-950 to-brand-950" />
        <svg className="absolute inset-0 h-full w-full" viewBox="0 0 1200 520" fill="none" aria-hidden="true">
          <path d="M-40 420 C 320 340, 760 200, 1260 70" stroke="white" strokeOpacity="0.10" strokeWidth="1.5" strokeDasharray="7 10" />
          <path d="M-40 470 C 360 400, 820 280, 1260 150" stroke="white" strokeOpacity="0.06" strokeWidth="1.5" strokeDasharray="7 10" />
          <circle cx="930" cy="118" r="4" fill="white" fillOpacity="0.55" />
        </svg>

        <div className="relative mx-auto max-w-6xl px-6 pt-16 pb-14 sm:pt-20">
          <span className="inline-flex items-center gap-1.5 rounded-full border border-white/15 bg-white/5 px-3 py-1 text-xs font-medium text-white/70">
            <span className="h-1.5 w-1.5 rounded-full bg-accent-500" />
            Real schedules · live fares · scannable boarding passes
          </span>
          <h1 className="mt-5 max-w-2xl text-4xl font-semibold leading-[1.1] tracking-tight text-white sm:text-5xl">
            Where would you like to fly?
          </h1>
          <p className="mt-4 max-w-lg text-base text-white/60">
            Search a year of departures across 30 routes, pick your seat from the actual cabin, and
            carry a boarding pass you can scan. No account needed to look.
          </p>

          {/* Embedded search */}
          <form
            onSubmit={search}
            className="mt-8 grid items-end gap-3 rounded-2xl border border-white/10 bg-white p-4 shadow-[var(--shadow-lift)] md:grid-cols-[1fr_1fr_auto_auto]"
          >
            <AirportField label="From" value={origin} onChange={setOrigin} exclude={destination} />
            <AirportField label="To" value={destination} onChange={setDestination} exclude={origin} />
            <label className="text-sm">
              <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                Depart
              </span>
              <input
                type="date"
                value={date}
                min={todayIso()}
                onChange={(event) => setDate(event.target.value)}
                className="tabular w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm outline-none transition focus:border-brand-500 focus:ring-4 focus:ring-brand-500/15"
              />
            </label>
            <button
              type="submit"
              disabled={sameAirport}
              className="inline-flex h-[42px] items-center justify-center gap-2 rounded-xl bg-brand-600 px-6 text-sm font-semibold text-white shadow-[var(--shadow-btn)] transition-all hover:bg-brand-700 hover:-translate-y-0.5 focus-visible:ring-2 focus-visible:ring-brand-500/50 focus-visible:outline-none disabled:cursor-not-allowed disabled:bg-slate-300 disabled:hover:translate-y-0"
            >
              <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true">
                <path d="M15.5 14h-.79l-.28-.27a6.5 6.5 0 1 0-.7.7l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0A4.5 4.5 0 1 1 14 9.5 4.49 4.49 0 0 1 9.5 14z" />
              </svg>
              Search
            </button>
            {sameAirport ? (
              <p className="text-sm text-red-600 md:col-span-4">Origin and destination must be different.</p>
            ) : null}
          </form>

          {/* Stats */}
          <div className="mt-12 grid grid-cols-2 gap-6 border-t border-white/10 pt-8 sm:grid-cols-4">
            <Stat value={30} label="Routes" />
            <Stat value={16} label="Airports" />
            <Stat value={11000} suffix="+" label="Departures" />
            <Stat value={4} label="Cabin classes" />
          </div>
        </div>
      </section>

      {/* Popular destinations */}
      <section className="mx-auto max-w-6xl px-6 py-14">
        <div className="flex items-end justify-between">
          <div>
            <h2 className="text-xl font-semibold tracking-tight text-slate-900">Popular destinations</h2>
            <p className="mt-1 text-sm text-slate-500">Departing London Heathrow — tap to see live fares.</p>
          </div>
          <Link to="/search" className="hidden text-sm font-medium text-brand-700 hover:underline sm:block">
            All routes →
          </Link>
        </div>
        <div className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {DESTINATIONS.map((dest) => (
            <Link
              key={dest.to}
              to={`/search?from=LHR&to=${dest.to}`}
              className="card-hover group relative block overflow-hidden rounded-xl"
            >
              <div className={`relative h-40 bg-gradient-to-br ${dest.tint}`}>
                <div className="grid-texture absolute inset-0 opacity-60" />
                <div className="absolute inset-0 bg-gradient-to-t from-black/45 to-transparent" />
                <svg className="absolute right-4 top-4 h-6 w-6 fill-white/80" viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z" />
                </svg>
                <div className="absolute bottom-4 left-4">
                  <div className="text-lg font-semibold text-white">{dest.city}</div>
                  <div className="text-xs text-white/75">
                    {dest.blurb} · LHR → {dest.to}
                  </div>
                </div>
              </div>
              <div className="flex items-center justify-between px-4 py-3">
                <span className="text-sm text-slate-500">Direct flights</span>
                <span className="text-sm font-semibold text-brand-700 transition group-hover:translate-x-0.5">
                  See fares →
                </span>
              </div>
            </Link>
          ))}
        </div>
      </section>

      {/* Why SkyBook */}
      <section className="border-y border-slate-200 bg-white">
        <div className="mx-auto max-w-6xl px-6 py-14">
          <h2 className="text-xl font-semibold tracking-tight text-slate-900">Built like the real thing</h2>
          <p className="mt-1 max-w-xl text-sm text-slate-500">
            Not a mockup — a full booking platform behind the scenes, all the way to the gate.
          </p>
          <div className="mt-8 grid gap-6 md:grid-cols-3">
            {FEATURES.map((feature) => (
              <div key={feature.title} className="card p-6">
                <span className="grid h-11 w-11 place-items-center rounded-xl bg-brand-50 text-brand-700">
                  <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.6">
                    <path d={feature.icon} strokeLinecap="round" strokeLinejoin="round" />
                  </svg>
                </span>
                <h3 className="mt-4 text-base font-semibold text-slate-900">{feature.title}</h3>
                <p className="mt-1.5 text-sm text-slate-600">{feature.body}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* CTA */}
      <section className="mx-auto max-w-6xl px-6 py-16">
        <div className="relative overflow-hidden rounded-2xl bg-brand-900 px-8 py-12 text-center">
          <div className="grid-texture absolute inset-0" />
          <div className="relative">
            <h2 className="text-2xl font-semibold tracking-tight text-white">Ready when you are.</h2>
            <p className="mx-auto mt-2 max-w-md text-sm text-white/60">
              Find a flight in seconds. Create an account only when you're ready to book.
            </p>
            <Link
              to="/search"
              className="mt-6 inline-flex items-center gap-2 rounded-xl bg-white px-6 py-3 text-sm font-semibold text-brand-900 shadow-sm transition hover:-translate-y-0.5 hover:bg-slate-50"
            >
              Search flights →
            </Link>
          </div>
        </div>
      </section>
    </>
  );
}
