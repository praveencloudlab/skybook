import { useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  AIRPORTS,
  POPULAR_ROUTES,
  flightsApi,
  type Flight,
  type Itinerary,
  type SearchCriteria,
} from '../../api/flights';
import { quotesApi, type TravelClass } from '../../api/quotes';
import { ErrorAlert } from '../../components/Alert';
import { BookingWidget, type BookingSearch } from '../../components/BookingWidget';
import { DateStrip } from '../../components/DateStrip';
import { ItineraryCard } from '../../components/ItineraryCard';
import type { Travellers } from '../../components/TravellersPicker';
import { ApiError } from '../../lib/errors';
import { addDaysIso, dayAndMonth, todayIso } from '../../lib/format';
import { SearchFilters } from './SearchFilters';
import { fareAlertsApi } from '../../api/alerts';
import { useSession } from '../auth/useSession';
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

const CABINS: TravelClass[] = ['ECONOMY', 'PREMIUM_ECONOMY', 'BUSINESS', 'FIRST'];

function knownCabin(value: string | null): TravelClass {
  return CABINS.includes(value as TravelClass) ? (value as TravelClass) : 'ECONOMY';
}

export function SearchPage({
  onSelectFlight,
}: {
  /**
   * returnFlight present when a round trip was selected; connection present
   * when a same-carrier through-ticket (its onward legs after `flight`).
   */
  onSelectFlight?: (
    flight: Flight,
    travellers: Travellers,
    cabin: TravelClass,
    returnFlight?: Flight,
    connection?: Flight[],
  ) => void;
}) {
  // Deep links from the landing page (its hero widget and destination cards)
  // arrive as ?from=&to=&date=&adults=&children=&infants=&cabin= and prefill
  // + auto-run the search below.
  const [params] = useSearchParams();
  // The widget owns the form state; the page keeps what the last search RAN
  // with, so Select hands the journey the party/cabin that produced results.
  // Tomorrow, not today: same-day departures may already have left, and an
  // empty first result is a poor first impression of a working system.
  const [initial] = useState<BookingSearch>(() => {
    const date = params.get('date') || addDaysIso(todayIso(), 1);
    // A deep-linked round trip carries ?return= (landing hero); accept it
    // only when it is a real date on or after the outbound.
    const returnParam = params.get('return');
    const returnDate =
      returnParam && /^\d{4}-\d{2}-\d{2}$/.test(returnParam) && returnParam >= date
        ? returnParam
        : undefined;
    return {
      origin: knownCode(params.get('from'), 'LHR'),
      destination: knownCode(params.get('to'), 'DXB'),
      date,
      ...(returnDate ? { returnDate } : {}),
      travellers: {
        adults: Math.max(1, Number(params.get('adults')) || 1),
        children: Math.max(0, Number(params.get('children')) || 0),
        infants: Math.max(0, Number(params.get('infants')) || 0),
      },
      cabin: knownCabin(params.get('cabin')),
    };
  });
  const [widgetKey, setWidgetKey] = useState(0);
  const [widgetInitial, setWidgetInitial] = useState<BookingSearch>(initial);
  // Round trip: the outbound picked in phase one, awaiting the return pick.
  // Seeded from the deep link so a landing-page round trip STAYS a round trip.
  const [returnDate, setReturnDate] = useState<string | undefined>(initial.returnDate);
  const [outbound, setOutbound] = useState<Flight | null>(null);
  // A through-ticketed outbound holds its onward legs too - the whole
  // connection joins the same PNR alongside the return.
  const [outboundConnection, setOutboundConnection] = useState<Flight[]>([]);
  // Multi-city: the legs to fly (from the widget), which one is being picked,
  // and the flights chosen so far - booked as segments of ONE PNR at the end.
  const [multiLegs, setMultiLegs] = useState<SearchCriteria[] | null>(null);
  const [legIndex, setLegIndex] = useState(0);
  const [pickedLegs, setPickedLegs] = useState<Flight[]>([]);
  const [party, setParty] = useState<{ travellers: Travellers; cabin: TravelClass }>({
    travellers: initial.travellers,
    cabin: initial.cabin,
  });

  const [results, setResults] = useState<Flight[] | null>(null);
  const [itins, setItins] = useState<Itinerary[] | null>(null);
  // Multi-select: which stop counts are shown (checkboxes in the rail).
  const [stopsChecked, setStopsChecked] = useState<boolean[]>([true, true, true]);
  const [searched, setSearched] = useState<SearchCriteria | null>(null);
  const [filters, setFilters] = useState<FilterState | null>(null);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  const visible = useMemo(
    () => (results && filters ? applyFilters(results, filters) : (results ?? [])),
    [results, filters],
  );
  const visibleItins = useMemo(() => {
    if (!itins) {
      return [];
    }
    const firstLegOk = new Set(visible.map((f) => f.id));
    return itins
      .filter((t) => firstLegOk.has(t.legs[0].id))
      .filter((t) => stopsChecked[t.stops] ?? true)
      // Multi-city legs join ONE booking, so a mixed-carrier self-transfer
      // (which is separate tickets by definition) can't be a leg of it.
      .filter((t) => !multiLegs || t.stops === 0 || t.sameCarrier);
  }, [itins, visible, stopsChecked, multiLegs]);

  // Day-floor fares for pricing the result cards ("from £X pp"): keyed by
  // departure date, covering the searched day +2 (overnight connection legs).
  // Same deterministic source the date strip and quote page use, so the card
  // price can never disagree with checkout.
  const [legFares, setLegFares] = useState<Map<string, number>>(new Map());
  // Fare watch: one click on the results header creates a server-side alert
  // for the searched route/date/cabin; reset whenever a new search runs.
  const { signedIn } = useSession();
  const [watchState, setWatchState] = useState<'idle' | 'busy' | 'watching'>('idle');

  async function runSearch(criteria: SearchCriteria, cabin: TravelClass = party.cabin) {
    setBusy(true);
    setError(null);
    quotesApi
      .fareCalendar(criteria.origin, criteria.destination, criteria.date, addDaysIso(criteria.date, 2), cabin)
      .then((days) => setLegFares(new Map(days.map((d) => [d.date, Number(d.minFare)]))))
      .catch(() => setLegFares(new Map()));
    try {
      const trips = await flightsApi.itineraries(criteria);
      const firstLegs = trips.map((t) => t.legs[0]);
      setItins(trips);
      setResults(firstLegs);
      setFilters(initialFilters(firstLegs));
      setStopsChecked([true, true, true]);
      setSearched(criteria);
      setWatchState('idle');
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
      setResults(null);
      setItins(null);
      setFilters(null);
    } finally {
      setBusy(false);
    }
  }

  function handleSearch(search: BookingSearch) {
    setParty({ travellers: search.travellers, cabin: search.cabin });
    setReturnDate(search.legs ? undefined : search.returnDate);
    setOutbound(null);
    setOutboundConnection([]);
    setMultiLegs(search.legs ?? null);
    setLegIndex(0);
    setPickedLegs([]);
    // Cabin passed explicitly: setParty hasn't landed yet in this tick.
    void runSearch(
      search.legs
        ? search.legs[0]
        : { origin: search.origin, destination: search.destination, date: search.date },
      search.cabin,
    );
  }

  /**
   * Multi-city: bank this leg's flight(s) and move to the next leg's search;
   * after the last leg the whole journey heads to fares as ONE booking
   * (first flight + the rest as onward segments).
   */
  function advanceMultiCity(flights: Flight[]) {
    if (!multiLegs || !onSelectFlight) return;
    const picked = [...pickedLegs, ...flights];
    if (legIndex + 1 < multiLegs.length) {
      setPickedLegs(picked);
      setLegIndex(legIndex + 1);
      void runSearch(multiLegs[legIndex + 1]);
    } else {
      onSelectFlight(picked[0], party.travellers, party.cabin, undefined, picked.slice(1));
    }
  }

  function pickRoute(route: { origin: string; destination: string }) {
    // Remount the widget with the chosen route so its tiles reflect it.
    const next = { ...widgetInitial, origin: route.origin, destination: route.destination };
    setWidgetInitial(next);
    setWidgetKey((k) => k + 1);
    void runSearch({ origin: route.origin, destination: route.destination, date: next.date });
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
      void runSearch({ origin: from, destination: to, date: initial.date });
    }
    // Run only on mount; the deep link is read once.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <>
      {/* Teal band the booking widget sits on. NOT overflow-hidden: the
          widget's calendar and guests panels open BELOW the band and must not
          be clipped at its edge (the decorative layers are all inset-0). z-10
          keeps those panels above the results that follow. */}
      <div className="relative z-10 bg-brand-950">
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

          {/* The premium-carrier booking widget: tabs, field tiles, the
              Guests-and-Cabin panel and the three-month fare calendar. */}
          <div className="mt-8">
            <BookingWidget key={widgetKey} initial={widgetInitial} busy={busy} onSearch={handleSearch} />
          </div>
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

        {multiLegs ? (
          <div className="mt-4 flex flex-wrap items-center justify-between gap-2 rounded-2xl bg-brand-50 px-4 py-2.5 text-sm text-brand-900 ring-1 ring-inset ring-brand-100">
            <span>
              <span className="font-bold">Multi-city · leg {legIndex + 1} of {multiLegs.length}:</span>{' '}
              <span className="tabular font-semibold">
                {multiLegs[legIndex].origin} → {multiLegs[legIndex].destination}
              </span>
              {pickedLegs.length ? (
                <span className="ml-2 text-xs text-brand-700">
                  picked: {pickedLegs.map((f) => f.flightNumber).join(' · ')}
                </span>
              ) : null}
            </span>
            {pickedLegs.length ? (
              <button
                type="button"
                onClick={() => {
                  setLegIndex(0);
                  setPickedLegs([]);
                  void runSearch(multiLegs[0]);
                }}
                className="font-bold text-brand-700 underline-offset-2 hover:underline"
              >
                Start over
              </button>
            ) : null}
          </div>
        ) : null}

        {outbound ? (
          <div className="mt-4 flex flex-wrap items-center justify-between gap-2 rounded-2xl bg-emerald-50 px-4 py-2.5 text-sm text-emerald-900 ring-1 ring-inset ring-emerald-200">
            <span>
              <span className="font-bold">Outbound selected:</span> {outbound.flightNumber}
              {outboundConnection.map((leg) => ` + ${leg.flightNumber}`).join('')}{' '}
              {outbound.originAirportCode} →{' '}
              {(outboundConnection[outboundConnection.length - 1] ?? outbound).destinationAirportCode}
              {outboundConnection.length ? ' (through-ticket)' : ''} — now choose your{' '}
              <span className="font-bold">return</span> flight.
            </span>
            <button
              type="button"
              onClick={() => {
                setOutbound(null);
                setOutboundConnection([]);
                if (searched) {
                  void runSearch({ origin: searched.destination, destination: searched.origin, date: widgetInitial.date });
                }
              }}
              className="font-bold text-emerald-700 underline-offset-2 hover:underline"
            >
              Change outbound
            </button>
          </div>
        ) : null}

        {/* Date-price strip: the same route day by day, cheapest per-guest
            fare each - tap a day to re-search it. */}
        {searched ? (
          <div className="mt-4">
            <DateStrip
              origin={searched.origin}
              destination={searched.destination}
              date={searched.date}
              cabin={party.cabin}
              onPickDate={(day) => {
                const next = { ...widgetInitial, date: day };
                setWidgetInitial(next);
                setWidgetKey((k) => k + 1);
                void runSearch({ origin: searched.origin, destination: searched.destination, date: day });
              }}
            />
          </div>
        ) : null}

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
                  <SearchFilters
                    results={results}
                    state={filters}
                    onChange={setFilters}
                    stopCounts={[0, 1, 2].map((n) => itins?.filter((t) => t.stops === n).length ?? 0)}
                    stopsChecked={stopsChecked}
                    onToggleStop={(n) =>
                      setStopsChecked((prev) => prev.map((v, i) => (i === n ? !v : v)))
                    }
                  />
                </div>
              </aside>

              {/* Result list. */}
              <section className="space-y-3">
                <div className="flex flex-wrap items-baseline justify-between gap-2 px-1">
                  <p className="text-sm text-slate-600">
                    <span className="font-semibold text-slate-900">{visibleItins.length}</span> trip
                    {visibleItins.length === 1 ? '' : 's'}
                    {returnDate ? (
                      <span className="ml-2 rounded-full bg-brand-50 px-2 py-0.5 text-xs font-semibold text-brand-700 ring-1 ring-inset ring-brand-100">
                        Round trip · pick your {outbound ? 'return' : 'outbound'} flight
                      </span>
                    ) : null}
                    {signedIn && searched ? (
                      <button
                        type="button"
                        disabled={watchState === 'watching' || watchState === 'busy'}
                        onClick={async () => {
                          setWatchState('busy');
                          try {
                            await fareAlertsApi.create({
                              originAirportCode: searched.origin,
                              destinationAirportCode: searched.destination,
                              travelDate: searched.date,
                              travelClass: party.cabin,
                            });
                            setWatchState('watching');
                          } catch {
                            setWatchState('idle');
                          }
                        }}
                        className="ml-2 rounded-full border border-accent-500 px-2.5 py-0.5 text-xs font-bold text-accent-600 transition hover:bg-accent-500 hover:text-white disabled:cursor-default disabled:border-emerald-500 disabled:bg-emerald-50 disabled:text-emerald-700"
                      >
                        {watchState === 'watching' ? '✓ Watching this fare' : '🔔 Watch this fare'}
                      </button>
                    ) : null}
                  </p>
                  <p className="tabular text-xs text-slate-500">
                    {searched.origin} → {searched.destination} · {dayAndMonth(`${searched.date}T00:00`)}
                  </p>
                </div>

                {visibleItins.length === 0 ? (
                  <p className="card px-4 py-3 text-sm text-slate-600">
                    No trips match these filters. Widen the stops, airline or time selection.
                  </p>
                ) : (
                  visibleItins.map((trip) => (
                    <ItineraryCard
                      key={trip.legs.map((l) => l.id).join('-')}
                      itinerary={trip}
                      fareForDate={(isoDate) => legFares.get(isoDate)}
                      onSelectLeg={
                        onSelectFlight
                          ? (leg) => {
                              if (multiLegs) {
                                advanceMultiCity([leg]);
                                return;
                              }
                              if (returnDate && !outbound && searched) {
                                // Phase one of a round trip: hold the outbound,
                                // search the inbound (route swapped).
                                setOutbound(leg);
                                void runSearch({
                                  origin: searched.destination,
                                  destination: searched.origin,
                                  date: returnDate,
                                });
                                return;
                              }
                              if (outbound) {
                                onSelectFlight(outbound, party.travellers, party.cabin, leg, outboundConnection);
                                return;
                              }
                              onSelectFlight(leg, party.travellers, party.cabin);
                            }
                          : undefined
                      }
                      onSelectItinerary={
                        // Same-carrier through-ticket: all legs, ONE booking.
                        // In round-trip phase ONE the whole connection is held
                        // as the outbound and joins the same PNR as the
                        // return; in phase TWO the return must be a single
                        // flight (request shape), so through cards fall back
                        // to per-leg booking there.
                        onSelectFlight && (!returnDate || !outbound)
                          ? (tripLegs) => {
                              if (multiLegs) {
                                advanceMultiCity(tripLegs);
                                return;
                              }
                              if (returnDate && !outbound && searched) {
                                setOutbound(tripLegs[0]);
                                setOutboundConnection(tripLegs.slice(1));
                                void runSearch({
                                  origin: searched.destination,
                                  destination: searched.origin,
                                  date: returnDate,
                                });
                                return;
                              }
                              onSelectFlight(
                                tripLegs[0],
                                party.travellers,
                                party.cabin,
                                undefined,
                                tripLegs.slice(1),
                              );
                            }
                          : undefined
                      }
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
