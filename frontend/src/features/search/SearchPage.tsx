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
      {/* A short brand band so the search form has something to sit against -
          without it the page opens as a bare form on grey, which is what made
          the app feel unfinished. */}
      <div className="relative overflow-hidden bg-brand-900">
        <div className="absolute inset-0 bg-gradient-to-br from-brand-800 via-brand-900 to-slate-950" />
        <div className="absolute -top-20 -right-10 h-72 w-72 rounded-full bg-brand-500/20 blur-3xl" />
        <svg
          className="absolute inset-0 h-full w-full"
          viewBox="0 0 1200 200"
          fill="none"
          aria-hidden="true"
        >
          <path
            d="M-50 190 C 300 150, 700 60, 1250 10"
            stroke="white"
            strokeOpacity="0.12"
            strokeWidth="1.5"
            strokeDasharray="6 8"
          />
        </svg>
        <div className="relative mx-auto max-w-5xl px-6 pt-10 pb-16">
          <h1 className="text-3xl font-semibold tracking-tight text-white">Where to?</h1>
          <p className="mt-1.5 text-sm text-white/60">
            Thirty routes, a year of departures, real seat maps.
          </p>
        </div>
      </div>

    <main className="mx-auto max-w-5xl px-6 pb-12">
      <form
        onSubmit={handleSubmit}
        // Lifted onto the band so the form reads as the primary action.
        className="card relative -mt-9 grid gap-3 p-4 shadow-[0_8px_24px_rgb(15_23_42/0.12)] sm:grid-cols-[1fr_1fr_auto_auto]"
      >
        <label className="text-sm">
          <span className="mb-1 block font-medium text-slate-700">From</span>
          <select
            value={origin}
            onChange={(e) => setOrigin(e.target.value)}
            className="w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm shadow-sm outline-none transition focus:border-brand-500 focus:ring-2 focus:ring-brand-500/30"
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
            className="w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm shadow-sm outline-none transition focus:border-brand-500 focus:ring-2 focus:ring-brand-500/30"
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
            className="tabular w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm shadow-sm outline-none transition focus:border-brand-500 focus:ring-2 focus:ring-brand-500/30"
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
                className="card px-3 py-2 text-left text-sm transition hover:border-brand-300 hover:bg-brand-50/40"
              >
                <span className="tabular font-medium text-slate-900">
                  {route.origin} → {route.destination}
                </span>
                <span className="block text-xs text-slate-500">{route.label}</span>
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
