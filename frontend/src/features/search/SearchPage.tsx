import { useMemo, useState, type FormEvent } from 'react';
import {
  POPULAR_ROUTES,
  flightsApi,
  type Flight,
  type SearchCriteria,
} from '../../api/flights';
import { ErrorAlert } from '../../components/Alert';
import { AirportField } from '../../components/AirportField';
import { FlightCard } from '../../components/FlightCard';
import { ApiError } from '../../lib/errors';
import { addDaysIso, dayAndMonth, todayIso } from '../../lib/format';
import { SearchFilters } from './SearchFilters';
import { applyFilters, initialFilters, type FilterState } from './filters';

/**
 * Flight search (FRONTEND_MODULE.md §5 screen 2, §10.2/§10.4).
 *
 * <p>Public: a visitor searches and browses before any account exists, the way
 * every travel site works - login is only required once they choose to book.
 *
 * <p>Shaped like a real metasearch results page: a persistent search bar on the
 * brand band up top, a filter rail down the left once there are results, and the
 * flights themselves as dense, scannable cards on the right.
 */
export function SearchPage({ onSelectFlight }: { onSelectFlight?: (flight: Flight) => void }) {
  const [origin, setOrigin] = useState('LHR');
  const [destination, setDestination] = useState('JFK');
  // Tomorrow, not today: same-day departures may already have left, and an
  // empty first result is a poor first impression of a working system.
  const [date, setDate] = useState(addDaysIso(todayIso(), 1));

  const [results, setResults] = useState<Flight[] | null>(null);
  const [searched, setSearched] = useState<SearchCriteria | null>(null);
  const [filters, setFilters] = useState<FilterState | null>(null);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  const visible = useMemo(
    () => (results && filters ? applyFilters(results, filters) : (results ?? [])),
    [results, filters],
  );

  async function runSearch(criteria: SearchCriteria) {
    setBusy(true);
    setError(null);
    try {
      const flights = await flightsApi.search(criteria);
      flights.sort((a, b) => a.departureTime.localeCompare(b.departureTime));
      setResults(flights);
      setFilters(initialFilters(flights));
      setSearched(criteria);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
      setResults(null);
      setFilters(null);
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

  function swap() {
    setOrigin(destination);
    setDestination(origin);
  }

  const sameAirport = origin === destination;

  return (
    <>
      {/* Brand band the search bar sits on - a modern travel hero, not a
          form-on-grey. */}
      <div className="relative overflow-hidden bg-gradient-to-br from-brand-700 via-brand-800 to-brand-950">
        <div className="absolute -top-24 -left-16 h-80 w-80 rounded-full bg-brand-400/25 blur-3xl" />
        <div className="absolute -top-10 right-0 h-72 w-72 rounded-full bg-fuchsia-500/20 blur-3xl" />
        <svg className="absolute inset-0 h-full w-full" viewBox="0 0 1200 260" fill="none" aria-hidden="true">
          <path
            d="M-50 240 C 300 190, 700 80, 1250 20"
            stroke="white"
            strokeOpacity="0.14"
            strokeWidth="1.5"
            strokeDasharray="6 8"
          />
          <circle cx="900" cy="66" r="4" fill="white" fillOpacity="0.7" />
        </svg>

        <div className="relative mx-auto max-w-6xl px-6 pt-12 pb-24">
          <h1 className="text-3xl font-semibold tracking-tight text-white sm:text-4xl">
            Millions of real departures. One simple search.
          </h1>
          <p className="mt-2 max-w-md text-sm text-white/70">
            Search a year of schedules, pick your seat from the actual cabin, and carry a boarding
            pass you can scan. No account needed to look.
          </p>
        </div>
      </div>

      <main className="mx-auto max-w-6xl px-6 pb-12">
        {/* Search bar, lifted onto the band. */}
        <form
          onSubmit={handleSubmit}
          className="card relative -mt-14 grid items-end gap-3 p-4 shadow-[var(--shadow-lift)] md:grid-cols-[1fr_auto_1fr_auto_auto]"
        >
          <AirportField label="From" value={origin} onChange={setOrigin} exclude={destination} />

          {/* Swap - the little control the screenshot has between the two fields. */}
          <button
            type="button"
            onClick={swap}
            aria-label="Swap origin and destination"
            className="mb-0.5 hidden h-10 w-10 shrink-0 place-items-center self-end rounded-full border border-slate-200 bg-white text-slate-500 transition hover:border-brand-300 hover:text-brand-600 md:grid"
          >
            <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true">
              <path d="M7 7h11l-3-3 1.4-1.4L21.8 8 16.4 13.4 15 12l3-3H7V7zm10 10H6l3 3-1.4 1.4L2.2 16 7.6 10.6 9 12l-3 3h11v2z" />
            </svg>
          </button>

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
            disabled={sameAirport || busy}
            className="inline-flex h-[42px] items-center justify-center gap-2 rounded-xl bg-brand-600 px-6 text-sm font-semibold text-white shadow-[var(--shadow-btn)] transition-all hover:bg-brand-500 hover:-translate-y-0.5 focus-visible:ring-2 focus-visible:ring-brand-500/50 focus-visible:outline-none disabled:cursor-not-allowed disabled:bg-brand-300 disabled:shadow-none disabled:hover:translate-y-0"
          >
            {busy ? (
              <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/60 border-t-transparent" />
            ) : (
              <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true">
                <path d="M15.5 14h-.79l-.28-.27a6.5 6.5 0 1 0-.7.7l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0A4.5 4.5 0 1 1 14 9.5 4.49 4.49 0 0 1 9.5 14z" />
              </svg>
            )}
            Search
          </button>

          {sameAirport ? (
            <p className="text-sm text-red-600 md:col-span-5">
              Origin and destination must be different.
            </p>
          ) : null}
        </form>

        {/* Before any search: curated routes that always return data (§10.4). */}
        {results === null && !busy ? (
          <section className="mt-10">
            <h2 className="text-sm font-semibold text-slate-700">Popular routes</h2>
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

        <div className="mt-8">
          <ErrorAlert error={error} />
        </div>

        {/* Results: left filter rail + right list. */}
        {results && searched && filters ? (
          results.length === 0 ? (
            <p className="card mt-2 px-4 py-3 text-sm text-slate-600">
              No flights on this route that day. Try another date, or pick one of the popular routes.
            </p>
          ) : (
            <div className="mt-4 grid gap-6 lg:grid-cols-[264px_1fr]">
              {/* Filters - a rail on desktop, a collapsible panel on mobile. */}
              <aside className="lg:block">
                <button
                  type="button"
                  onClick={() => setFiltersOpen((open) => !open)}
                  aria-expanded={filtersOpen}
                  className="card flex w-full items-center justify-between px-4 py-3 text-sm font-medium text-slate-700 lg:hidden"
                >
                  <span className="flex items-center gap-2">
                    <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true">
                      <path d="M3 5h18v2l-7 7v5l-4 2v-7L3 7z" />
                    </svg>
                    Filters
                  </span>
                  <span className="text-brand-600">{filtersOpen ? 'Hide' : 'Show'}</span>
                </button>
                <div className={(filtersOpen ? 'mt-3 block' : 'hidden') + ' lg:sticky lg:top-20 lg:mt-0 lg:block'}>
                  <SearchFilters results={results} state={filters} onChange={setFilters} />
                </div>
              </aside>

              {/* Result list. */}
              <section className="space-y-3">
                <div className="flex flex-wrap items-baseline justify-between gap-2 px-1">
                  <p className="text-sm text-slate-600">
                    <span className="font-semibold text-slate-900">{visible.length}</span>
                    {visible.length === results.length ? '' : ` of ${results.length}`} flight
                    {results.length === 1 ? '' : 's'}
                  </p>
                  <p className="tabular text-xs text-slate-500">
                    {searched.origin} → {searched.destination} · {dayAndMonth(`${searched.date}T00:00`)}
                  </p>
                </div>

                {visible.length === 0 ? (
                  <p className="card px-4 py-3 text-sm text-slate-600">
                    No flights match these filters. Widen the airline or time selection on the left.
                  </p>
                ) : (
                  visible.map((flight) => (
                    <FlightCard
                      key={flight.id}
                      flight={flight}
                      onSelect={onSelectFlight ? () => onSelectFlight(flight) : undefined}
                    />
                  ))
                )}
              </section>
            </div>
          )
        ) : null}
      </main>
    </>
  );
}
