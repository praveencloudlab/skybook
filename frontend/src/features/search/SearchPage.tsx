import { useState, type FormEvent } from 'react';
import {
  AIRPORTS,
  POPULAR_ROUTES,
  flightsApi,
  type Flight,
  type SearchCriteria,
} from '../../api/flights';
import { ErrorAlert } from '../../components/Alert';
import { Button } from '../../components/Button';
import { FlightCard } from '../../components/FlightCard';
import { ApiError } from '../../lib/errors';
import { addDaysIso, dayAndMonth, todayIso } from '../../lib/format';

/**
 * Flight search (FRONTEND_MODULE.md §5 screen 2, §10.4).
 *
 * <p>Opens on curated routes rather than an empty form. The seed holds ~11,000
 * flights across 30 routes, and a visitor who does not know which ones exist
 * would either search a route with no data and conclude the app is broken, or be
 * shown an undifferentiated wall of departures. One click on a known-good route
 * makes the platform demonstrate itself; the full form is right there for
 * anything else.
 */
export function SearchPage({ onSelectFlight }: { onSelectFlight?: (flight: Flight) => void }) {
  const [origin, setOrigin] = useState('LHR');
  const [destination, setDestination] = useState('JFK');
  // Tomorrow, not today: same-day departures may already have left, and an
  // empty first result is a poor first impression of a working system.
  const [date, setDate] = useState(addDaysIso(todayIso(), 1));

  const [results, setResults] = useState<Flight[] | null>(null);
  const [searched, setSearched] = useState<SearchCriteria | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  async function runSearch(criteria: SearchCriteria) {
    setBusy(true);
    setError(null);
    try {
      const flights = await flightsApi.search(criteria);
      // Earliest first: the order people actually think in.
      flights.sort((a, b) => a.departureTime.localeCompare(b.departureTime));
      setResults(flights);
      setSearched(criteria);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
      setResults(null);
    } finally {
      setBusy(false);
    }
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    void runSearch({ origin, destination, date });
  }

  function pickRoute(route: { origin: string; destination: string }) {
    setOrigin(route.origin);
    setDestination(route.destination);
    void runSearch({ origin: route.origin, destination: route.destination, date });
  }

  const sameAirport = origin === destination;

  return (
    <>
      {/* A vivid brand band so the search form has something to sit against.
          The colour wash + soft orbs read as a modern travel hero rather than
          the flat form-on-grey the app opened with before. */}
      <div className="relative overflow-hidden bg-gradient-to-br from-brand-700 via-brand-800 to-brand-950">
        <div className="absolute -top-24 -left-16 h-80 w-80 rounded-full bg-brand-400/25 blur-3xl" />
        <div className="absolute -top-10 right-0 h-72 w-72 rounded-full bg-fuchsia-500/20 blur-3xl" />
        <svg
          className="absolute inset-0 h-full w-full"
          viewBox="0 0 1200 260"
          fill="none"
          aria-hidden="true"
        >
          <path
            d="M-50 240 C 300 190, 700 80, 1250 20"
            stroke="white"
            strokeOpacity="0.14"
            strokeWidth="1.5"
            strokeDasharray="6 8"
          />
          <path
            d="M-50 260 C 360 230, 760 150, 1250 90"
            stroke="white"
            strokeOpacity="0.08"
            strokeWidth="1.5"
            strokeDasharray="6 8"
          />
          <circle cx="900" cy="66" r="4" fill="white" fillOpacity="0.7" />
        </svg>
        <div className="relative mx-auto max-w-6xl px-6 pt-14 pb-20">
          <span className="inline-flex items-center gap-1.5 rounded-full bg-white/10 px-3 py-1 text-xs font-medium text-white/80 ring-1 ring-white/15 backdrop-blur">
            ✦ 30 routes · a year of departures
          </span>
          <h1 className="mt-4 text-4xl font-semibold tracking-tight text-white sm:text-5xl">
            Where to next?
          </h1>
          <p className="mt-2 max-w-md text-sm text-white/70">
            Search real schedules, pick your seat from the actual cabin, and carry a boarding pass
            you can scan.
          </p>
        </div>
      </div>

    <main className="mx-auto max-w-6xl px-6 pb-12">
      <form
        onSubmit={handleSubmit}
        // Lifted onto the band so the form reads as the primary action.
        className="card relative -mt-10 grid gap-3 p-5 shadow-[var(--shadow-lift)] sm:grid-cols-[1fr_1fr_auto_auto]"
      >
        <label className="text-sm">
          <span className="mb-1 block font-medium text-slate-700">From</span>
          <select
            value={origin}
            onChange={(e) => setOrigin(e.target.value)}
            className="w-full rounded-xl border border-slate-200 bg-slate-50/60 px-3.5 py-2.5 text-sm outline-none transition focus:bg-white focus:border-brand-500 focus:ring-4 focus:ring-brand-500/15"
          >
            {AIRPORTS.map((airport) => (
              <option key={airport.code} value={airport.code}>
                {airport.code} · {airport.city}
              </option>
            ))}
          </select>
        </label>

        <label className="text-sm">
          <span className="mb-1 block font-medium text-slate-700">To</span>
          <select
            value={destination}
            onChange={(e) => setDestination(e.target.value)}
            className="w-full rounded-xl border border-slate-200 bg-slate-50/60 px-3.5 py-2.5 text-sm outline-none transition focus:bg-white focus:border-brand-500 focus:ring-4 focus:ring-brand-500/15"
          >
            {AIRPORTS.map((airport) => (
              <option key={airport.code} value={airport.code}>
                {airport.code} · {airport.city}
              </option>
            ))}
          </select>
        </label>

        <label className="text-sm">
          <span className="mb-1 block font-medium text-slate-700">Departing</span>
          <input
            type="date"
            value={date}
            min={todayIso()}
            onChange={(e) => setDate(e.target.value)}
            className="tabular w-full rounded-xl border border-slate-200 bg-slate-50/60 px-3.5 py-2.5 text-sm outline-none transition focus:bg-white focus:border-brand-500 focus:ring-4 focus:ring-brand-500/15"
          />
        </label>

        <div className="flex items-end">
          <Button type="submit" busy={busy} disabled={sameAirport} className="w-full">
            Search
          </Button>
        </div>

        {sameAirport ? (
          <p className="text-sm text-red-600 sm:col-span-4">
            Origin and destination must be different.
          </p>
        ) : null}
      </form>

      {results === null && !busy ? (
        <section className="mt-8">
          <h2 className="text-sm font-medium text-slate-700">Popular routes</h2>
          <div className="mt-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {POPULAR_ROUTES.map((route) => (
              <button
                key={route.label}
                type="button"
                onClick={() => pickRoute(route)}
                className="card card-hover flex items-center gap-3 px-4 py-3 text-left text-sm"
              >
                <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-brand-50 text-brand-600">
                  <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true">
                    <path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z" />
                  </svg>
                </span>
                <span className="min-w-0">
                  <span className="tabular block font-semibold text-slate-900">
                    {route.origin} → {route.destination}
                  </span>
                  <span className="block truncate text-xs text-slate-500">{route.label}</span>
                </span>
              </button>
            ))}
          </div>
        </section>
      ) : null}

      <div className="mt-8 space-y-3">
        <ErrorAlert error={error} />

        {results && searched ? (
          <>
            <p className="text-sm text-slate-600">
              {results.length === 0
                ? 'No flights on this route that day.'
                : `${results.length} flight${results.length === 1 ? '' : 's'} · ${searched.origin} → ${searched.destination} · ${dayAndMonth(`${searched.date}T00:00`)}`}
            </p>

            {results.length === 0 ? (
              // Say what to do next. "No results" alone reads like a fault.
              <p className="card px-3 py-2 text-sm text-slate-600">
                Try another date, or pick one of the popular routes.
              </p>
            ) : (
              results.map((flight) => (
                <FlightCard
                  key={flight.id}
                  flight={flight}
                  onSelect={onSelectFlight ? () => onSelectFlight(flight) : undefined}
                />
              ))
            )}
          </>
        ) : null}
      </div>
    </main>
    </>
  );
}
