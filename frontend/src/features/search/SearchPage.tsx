import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  AIRPORTS,
  POPULAR_ROUTES,
  flightsApi,
  type Flight,
  type SearchCriteria,
} from '../../api/flights';
import { ErrorAlert } from '../../components/Alert';
import { AirportField } from '../../components/AirportField';
import { FlightCard } from '../../components/FlightCard';
import { ONE_ADULT, TravellersPicker, type Travellers } from '../../components/TravellersPicker';
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
function knownCode(code: string | null, fallback: string): string {
  return code && AIRPORTS.some((airport) => airport.code === code) ? code : fallback;
}

export function SearchPage({
  onSelectFlight,
}: {
  onSelectFlight?: (flight: Flight, travellers: Travellers) => void;
}) {
  // Deep links from the landing page (its hero search and destination cards)
  // arrive as ?from=&to=&date= and prefill + auto-run the search below.
  const [params] = useSearchParams();
  const [origin, setOrigin] = useState(() => knownCode(params.get('from'), 'LHR'));
  const [destination, setDestination] = useState(() => knownCode(params.get('to'), 'JFK'));
  // Tomorrow, not today: same-day departures may already have left, and an
  // empty first result is a poor first impression of a working system.
  const [date, setDate] = useState(() => params.get('date') || addDaysIso(todayIso(), 1));
  // Who travels - adults/children/infants, asked up front like every airline
  // site, so the rest of the journey (seat picks, passenger forms, date-of-
  // birth bounds, totals) is sized and typed correctly.
  const [travellers, setTravellers] = useState<Travellers>(ONE_ADULT);

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

  // Auto-run once when arriving from a landing-page deep link, so the visitor
  // lands directly on results rather than having to press Search again.
  const autoRan = useRef(false);
  useEffect(() => {
    if (autoRan.current) {
      return;
    }
    autoRan.current = true;
    const from = knownCode(params.get('from'), '');
    const to = knownCode(params.get('to'), '');
    if (from && to && from !== to) {
      void runSearch({ origin: from, destination: to, date: params.get('date') || date });
    }
    // Run only on mount; the deep link is read once.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const sameAirport = origin === destination;

  return (
    <>
      {/* Navy band the search bar sits on - restrained and engineered, not a
          glow. A faint grid texture + a single hairline flight path. */}
      <div className="relative overflow-hidden bg-brand-950">
        <div className="aurora" aria-hidden="true" />
        <div className="grid-texture absolute inset-0" />
        <div className="absolute inset-0 bg-gradient-to-b from-transparent via-brand-950/25 to-brand-950" />
        <svg className="absolute inset-0 h-full w-full" viewBox="0 0 1200 260" fill="none" aria-hidden="true">
          <path
            d="M-50 230 C 320 180, 720 90, 1250 30"
            stroke="white"
            strokeOpacity="0.12"
            strokeWidth="1.5"
            strokeDasharray="6 9"
          />
          <circle cx="900" cy="72" r="3.5" fill="white" fillOpacity="0.6" />
        </svg>

        <div className="relative mx-auto max-w-6xl px-6 pb-10 pt-12">
          <h1 className="display text-4xl text-white sm:text-5xl">
            Search flights
          </h1>
          <p className="mt-3 max-w-md text-sm text-white/70">
            A year of real schedules across 30 routes. Compare fares, pick your seat from the actual
            cabin — no account needed to look.
          </p>

          {/* The premium-carrier booking widget: trip-type tabs docked to the
              top of a white panel, outlined field tiles with gold icon discs,
              and the gold Search pill. z-20 so the guests popover stacks
              above the results below. */}
          <form onSubmit={handleSubmit} className="relative z-20 mt-8">
            {/* Tab rail: only one-way flying exists today - the other trip
                types are shown the way the airline shows them, but disabled
                honestly rather than pretending. */}
            <div className="inline-flex items-center gap-1 rounded-t-2xl bg-white px-2 pt-2">
              <span
                aria-disabled="true"
                title="Not available yet"
                className="cursor-not-allowed rounded-full px-4 py-1.5 text-sm font-semibold text-slate-400"
              >
                Round trip
              </span>
              <span className="rounded-full bg-brand-900 px-4 py-1.5 text-sm font-semibold text-white">
                One way
              </span>
              <span
                aria-disabled="true"
                title="Not available yet"
                className="cursor-not-allowed rounded-full px-4 py-1.5 text-sm font-semibold text-slate-400"
              >
                Multi-city
              </span>
            </div>

            <div className="rounded-b-2xl rounded-tr-2xl bg-white p-4 shadow-[var(--shadow-float)]">
              <div className="grid items-center gap-2 md:grid-cols-[1fr_auto_1fr_1fr_1fr_auto]">
                <AirportField label="From" value={origin} onChange={setOrigin} exclude={destination} />

                <button
                  type="button"
                  onClick={swap}
                  aria-label="Swap origin and destination"
                  className="hidden h-8 w-8 shrink-0 place-items-center rounded-full text-slate-400 transition hover:bg-slate-100 hover:text-slate-600 md:grid"
                >
                  <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true">
                    <path d="M7 7h11l-3-3 1.4-1.4L21.8 8 16.4 13.4 15 12l3-3H7V7zm10 10H6l3 3-1.4 1.4L2.2 16 7.6 10.6 9 12l-3 3h11v2z" />
                  </svg>
                </button>

                <AirportField label="To" value={destination} onChange={setDestination} exclude={origin} />

                <TravellersPicker value={travellers} onChange={setTravellers} />

                {/* Date tile, matching the others: gold disc + caption + value. */}
                <label className="flex cursor-pointer items-center gap-3 rounded-xl border border-slate-300 bg-white px-3 py-2 transition focus-within:border-brand-900 focus-within:ring-1 focus-within:ring-brand-900 hover:border-slate-400">
                  <span className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-accent-500 text-white">
                    <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true">
                      <path d="M19 4h-1V2h-2v2H8V2H6v2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2zm0 16H5V10h14zM5 8V6h14v2z" />
                    </svg>
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block text-xs font-semibold text-slate-500">Travelling when?</span>
                    <input
                      type="date"
                      value={date}
                      min={todayIso()}
                      onChange={(event) => setDate(event.target.value)}
                      className="tabular w-full border-0 bg-transparent p-0 text-[15px] font-bold text-slate-900 outline-none"
                    />
                  </span>
                </label>

                <button
                  type="submit"
                  disabled={sameAirport || busy}
                  className="inline-flex h-[52px] items-center justify-center gap-2 rounded-full bg-accent-500 px-8 text-base font-bold text-white transition-colors hover:bg-accent-600 focus-visible:ring-2 focus-visible:ring-accent-500/60 focus-visible:outline-none disabled:cursor-not-allowed disabled:bg-accent-200"
                >
                  {busy ? (
                    <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/60 border-t-transparent" />
                  ) : null}
                  Search
                </button>
              </div>

              {sameAirport ? (
                <p className="mt-2 text-sm font-medium text-red-600">
                  Origin and destination must be different.
                </p>
              ) : null}
            </div>
          </form>
        </div>
      </div>

      <main className="mx-auto max-w-6xl px-6 pb-12 pt-6">

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
                      onSelect={onSelectFlight ? () => onSelectFlight(flight, travellers) : undefined}
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
