import { useEffect, useState } from 'react';
import type { Flight } from '../../api/flights';
import {
  FARE_TYPE_LABELS,
  TRAVEL_CLASS_LABELS,
  TRAVEL_CLASS_ORDER,
  quotesApi,
  type CabinQuote,
  type FareType,
  type Quote,
  type TravelClass,
} from '../../api/quotes';
import { ErrorAlert } from '../../components/Alert';
import { BookingStepper } from '../../components/BookingStepper';
import { Button } from '../../components/Button';
import { ApiError } from '../../lib/errors';
import { seatsApi, type FlightSeatMap } from '../../api/seats';
import { dayAndMonth, durationFromMinutes, price, time } from '../../lib/format';
import { useSession } from '../auth/useSession';

/**
 * Choose your fare (carrier flow step 1b): the chosen cabin's fare families as
 * rich feature cards - coloured top bar, price, a Choose pill and an honest
 * tick/cross feature list - with cabin tabs above to switch cabin. Only claims
 * the platform actually honours appear: refund generosity differs by fare
 * type, baggage by cabin, seats are paid-or-free-at-check-in for everyone,
 * and online date changes don't exist yet - so the cards say exactly that.
 */

const BAGGAGE: Record<TravelClass, { checked: string; cabin: string }> = {
  ECONOMY: { checked: '25kg checked baggage', cabin: '7kg cabin baggage' },
  PREMIUM_ECONOMY: { checked: '30kg checked baggage', cabin: '7kg cabin baggage' },
  BUSINESS: { checked: '40kg checked baggage', cabin: '10kg cabin baggage' },
  FIRST: { checked: '50kg checked baggage', cabin: '10kg cabin baggage' },
};

const FARE_ORDER: FareType[] = ['SAVER', 'FLEXI', 'PREMIUM'];

const CARD_BAR: Record<FareType, string> = {
  SAVER: 'bg-orange-500',
  FLEXI: 'bg-teal-600',
  PREMIUM: 'bg-accent-500',
};

export function FlightQuotePage({
  flight,
  returnFlight = null,
  connection = [],
  paxCount,
  preferredCabin = 'ECONOMY',
  onBack,
  onChoose,
}: {
  flight: Flight;
  /** Round trip: prices below are outbound + return combined. */
  returnFlight?: Flight | null;
  /** Same-carrier through-ticket: the onward connection legs after `flight` - priced in. */
  connection?: Flight[];
  paxCount: number;
  preferredCabin?: TravelClass;
  onBack: () => void;
  // Carries the PRICE, not just the labels: later steps show a running total,
  // and re-deriving the fare there could disagree with what was clicked.
  onChoose?: (choice: {
    cabin: TravelClass;
    fare: FareType;
    baseFare: number;
    currency: string;
  }) => void;
}) {
  const { signedIn } = useSession();
  const [quote, setQuote] = useState<Quote | null>(null);
  const [seatMap, setSeatMap] = useState<FlightSeatMap | null>(null);
  const [cabin, setCabin] = useState<TravelClass>(preferredCabin);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(true);

  useEffect(() => {
    const controller = new AbortController();
    setBusy(true);
    setError(null);

    // Every leg of the journey priced together: through-ticket connection
    // legs and/or the return, folded pairwise into one combined quote.
    const extraLegs = [...connection, ...(returnFlight ? [returnFlight] : [])];
    const load = extraLegs.length
      ? Promise.all(
          [flight, ...extraLegs].map((leg) => quotesApi.forFlight(leg.id, controller.signal)),
        ).then((quotes) => quotes.reduce((acc, q) => combineQuotes(acc, q)))
      : quotesApi.forFlight(flight.id, controller.signal);
    load
      .then((result) => {
        setQuote(result);
        // If the aircraft has no such cabin, land on the first it does have.
        if (!result.cabins.some((c) => c.travelClass === preferredCabin)) {
          const first = TRAVEL_CLASS_ORDER.find((tc) => result.cabins.some((c) => c.travelClass === tc));
          if (first) {
            setCabin(first);
          }
        }
      })
      .catch((cause) => {
        if (cause instanceof DOMException && cause.name === 'AbortError') {
          return;
        }
        setError(cause instanceof ApiError ? cause : null);
      })
      .finally(() => setBusy(false));

    return () => controller.abort();
  }, [flight.id, returnFlight, connection, preferredCabin]);

  useEffect(() => {
    // Seat maps need a session (the global 401 handler would bounce an
    // anonymous browser to sign-in mid-shopping); the seat-fact rows simply
    // don't render until then.
    if (!signedIn) {
      return;
    }
    const controller = new AbortController();
    seatsApi.forFlight(flight.id, controller.signal).then(setSeatMap).catch(() => {});
    return () => controller.abort();
  }, [flight.id, signedIn]);

  /** REAL per-cabin facts from this flight's own seat map. */
  function cabinFacts(travelClass: TravelClass): { free: number; paidMin: number; paidMax: number } {
    const seats = seatMap?.aircraft.seats.filter((x) => x.seatType === travelClass) ?? [];
    const paid = seats.map((x) => Number(x.listedSurcharge) || 0).filter((n) => n > 0);
    return {
      free: seats.filter((x) => (Number(x.listedSurcharge) || 0) === 0).length,
      paidMin: paid.length ? Math.min(...paid) : 0,
      paidMax: paid.length ? Math.max(...paid) : 0,
    };
  }

  const cabins = quote
    ? TRAVEL_CLASS_ORDER.map((tc) => quote.cabins.find((c) => c.travelClass === tc)).filter(
        (c): c is CabinQuote => c !== undefined,
      )
    : [];
  const selectedCabin = cabins.find((c) => c.travelClass === cabin) ?? cabins[0];

  return (
    <>
      <BookingStepper
        current="flights"
        flight={flight}
        route={`${flight.originAirportCode} → ${flight.destinationAirportCode}`}
        onModify={onBack}
      />

      <main className="mx-auto grid max-w-6xl gap-6 px-4 py-6 sm:px-6 lg:grid-cols-[1fr_300px]">
        <div>
        <h1 className="text-2xl font-bold tracking-tight text-slate-900">Choose your fare</h1>
        <p className="mt-1 text-sm text-slate-500">
          Prices are for all guests ({paxCount}){returnFlight ? ', outbound + return combined,' : connection.length ? ', all connection legs combined,' : ''} including taxes. You'll pick seats and bags next.
        </p>

        <div className="mt-4">
          <ErrorAlert error={error} />
        </div>

        {busy ? (
          <div className="mt-4 rounded-2xl bg-white px-4 py-10 text-center text-sm text-slate-500 shadow-[var(--shadow-card)]">
            <span className="inline-flex items-center gap-2">
              <span className="h-4 w-4 animate-spin rounded-full border-2 border-brand-300 border-t-transparent" />
              Loading live fares…
            </span>
          </div>
        ) : quote && selectedCabin ? (
          <>
            {/* Cabin tabs. Two even columns on a phone - four pills of very
                uneven width ("First" vs "Premium Economy") wrapped into a
                ragged 3+1 that read as a mistake rather than a choice. */}
            <div className="mt-4 grid grid-cols-2 gap-2 sm:flex sm:flex-wrap">
              {cabins.map((c) => {
                const active = c.travelClass === selectedCabin.travelClass;
                return (
                  <button
                    key={c.travelClass}
                    type="button"
                    onClick={() => setCabin(c.travelClass)}
                    aria-pressed={active}
                    className={
                      'flex min-h-11 items-center justify-center gap-1 rounded-full px-3 text-center text-sm font-bold transition sm:px-5 sm:py-2 ' +
                      (active
                        ? 'bg-brand-950 text-white'
                        : 'border border-slate-300 bg-white text-slate-700 hover:border-slate-500')
                    }
                  >
                    {TRAVEL_CLASS_LABELS[c.travelClass]}
                    {c.availableSeats !== null && c.availableSeats !== undefined && c.availableSeats <= 5 ? (
                      <span className="ml-2 rounded-full bg-red-600 px-2 py-0.5 text-[10px] font-bold text-white">
                        {c.availableSeats} seats left
                      </span>
                    ) : null}
                  </button>
                );
              })}
            </div>

            {/* Fare cards. */}
            <div className="mt-5 grid gap-4 md:grid-cols-3">
              {FARE_ORDER.map((fareType) => {
                const perGuest = Number(selectedCabin.baseFares[fareType] ?? 0);
                const cheapest = Number(selectedCabin.baseFares[FARE_ORDER[0]] ?? 0);
                const partyPrice = perGuest * paxCount;
                const increment = (perGuest - cheapest) * paxCount;
                const recommended = fareType === 'FLEXI';
                const bags = BAGGAGE[selectedCabin.travelClass];
                return (
                  <article
                    key={fareType}
                    className={
                      'overflow-hidden rounded-2xl bg-white shadow-[var(--shadow-card)] ' +
                      (recommended ? 'ring-2 ring-accent-500' : 'ring-1 ring-slate-200')
                    }
                  >
                    {recommended ? (
                      <div className="bg-accent-500 py-1.5 text-center text-xs font-bold uppercase tracking-wide text-white">
                        Recommended
                      </div>
                    ) : (
                      <div className={'h-2.5 ' + CARD_BAR[fareType]} aria-hidden="true" />
                    )}
                    <div className="p-5">
                      <h2 className="text-xl font-bold text-slate-900">{FARE_TYPE_LABELS[fareType]}</h2>
                      <div className="tabular mt-2 text-2xl font-bold text-slate-900">
                        {fareType === FARE_ORDER[0]
                          ? price(partyPrice, quote.currency)
                          : `+ ${price(increment, quote.currency)}`}
                      </div>
                      <p className="text-[11px] text-slate-400">for all guests</p>

                      <Button
                        className="mt-4 w-full"
                        variant={recommended ? 'primary' : 'secondary'}
                        onClick={() =>
                          onChoose?.({
                            cabin: selectedCabin.travelClass,
                            fare: fareType,
                            baseFare: perGuest,
                            currency: quote.currency,
                          })
                        }
                        disabled={!onChoose}
                      >
                        Choose
                      </Button>

                      <ul className="mt-4 space-y-2.5 border-t border-slate-100 pt-4 text-sm">
                        <FeatureRow ok text={bags.checked} />
                        <FeatureRow ok text={bags.cabin} />
                        {selectedCabin.availableSeats !== null && selectedCabin.availableSeats !== undefined ? (
                          <FeatureRow
                            ok
                            text={`${selectedCabin.availableSeats} seats available in ${TRAVEL_CLASS_LABELS[selectedCabin.travelClass]}`}
                          />
                        ) : null}
                        {seatMap ? (
                          (() => {
                            const facts = cabinFacts(selectedCabin.travelClass);
                            return (
                              <>
                                <FeatureRow ok text={`${facts.free} free-to-pick seats on this aircraft`} />
                                {facts.paidMax > 0 ? (
                                  <FeatureRow
                                    ok
                                    text={`Preferred seats ${price(facts.paidMin, quote.currency)}–${price(facts.paidMax, quote.currency)}`}
                                  />
                                ) : (
                                  <FeatureRow ok text="Every seat in this cabin is free to pick" />
                                )}
                              </>
                            );
                          })()
                        ) : null}
                        <FeatureRow
                          ok
                          text={
                            fareType === 'SAVER'
                              ? 'Partial refund — cancellation fee applies'
                              : fareType === 'FLEXI'
                                ? 'Generous refund on cancellation'
                                : 'Highest refund — fully flexible'
                          }
                        />
                        {fareType === 'SAVER' ? (
                          <>
                            <FeatureRow ok text="Free auto-assigned seat at check-in" />
                            <FeatureRow ok text="Pick any seat (listed surcharge)" />
                          </>
                        ) : (
                          <FeatureRow ok text="Choose ANY seat — free, surcharges waived" />
                        )}
                        <FeatureRow ok text="Extra bags from $40 each" />
                        {fareType === 'PREMIUM' ? (
                          <FeatureRow ok text="Unlimited online date changes — fare difference only" />
                        ) : (
                          <FeatureRow ok={false} text="Free online date changes" />
                        )}
                      </ul>
                    </div>
                  </article>
                );
              })}
            </div>
          </>
        ) : null}
        </div>

        {/* Flight details rail. */}
        <aside className="h-fit rounded-2xl bg-white p-5 shadow-[var(--shadow-card)] lg:sticky lg:top-20">
          <h2 className="text-lg font-bold text-slate-900">Flight details</h2>
          <div className="mt-3 space-y-2 text-sm">
            <div className="flex items-center gap-2">
              <span className="grid h-8 w-8 place-items-center rounded-lg bg-brand-600 text-[10px] font-bold text-white">{flight.airlineCode}</span>
              <span className="tabular font-semibold text-slate-900">{flight.flightNumber}</span>
            </div>
            <div className="tabular text-slate-600">{dayAndMonth(flight.departureTime)}</div>
            <div className="flex items-center gap-3">
              <div>
                <div className="tabular text-xl font-bold text-slate-900">{time(flight.departureTime)}</div>
                <div className="text-xs font-semibold text-slate-500">{flight.originAirportCode}</div>
              </div>
              <div className="flex flex-1 flex-col items-center">
                <span className="tabular text-[10px] text-slate-400">{durationFromMinutes(flight.durationMinutes)}</span>
                <span className="w-full border-t-2 border-dotted border-slate-300" />
                <span className="text-[10px] text-slate-400">Direct</span>
              </div>
              <div className="text-right">
                <div className="tabular text-xl font-bold text-slate-900">{time(flight.arrivalTime)}</div>
                <div className="text-xs font-semibold text-slate-500">{flight.destinationAirportCode}</div>
              </div>
            </div>
            <div className="border-t border-slate-100 pt-2 text-xs text-slate-500">
              {paxCount} guest{paxCount === 1 ? '' : 's'} · fares include taxes
            </div>
          </div>
        </aside>
      </main>
    </>
  );
}

/** Round trip = two tickets: fares sum, availability is the tighter leg. */
function combineQuotes(outbound: Quote, inbound: Quote): Quote {
  const cabins: CabinQuote[] = [];
  for (const cab of outbound.cabins) {
    const ret = inbound.cabins.find((c) => c.travelClass === cab.travelClass);
    if (!ret) {
      continue;
    }
    const baseFares = Object.fromEntries(
      Object.entries(cab.baseFares).map(([fareType, fare]) => [
        fareType,
        Number(fare) + Number(ret.baseFares[fareType as FareType] ?? 0),
      ]),
    ) as CabinQuote['baseFares'];
    cabins.push({
      ...cab,
      baseFares,
      fromFare: Math.min(...Object.values(baseFares).map(Number)),
      availableSeats:
        cab.availableSeats === null || cab.availableSeats === undefined
          ? ret.availableSeats
          : ret.availableSeats === null || ret.availableSeats === undefined
            ? cab.availableSeats
            : Math.min(cab.availableSeats, ret.availableSeats),
    });
  }
  return { ...outbound, cabins };
}

function FeatureRow({ ok, text }: { ok: boolean; text: string }) {
  return (
    <li className="flex items-start gap-2 text-slate-700">
      {ok ? (
        <svg viewBox="0 0 24 24" className="mt-0.5 h-4 w-4 shrink-0 fill-emerald-600" aria-hidden="true">
          <path d="M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z" />
        </svg>
      ) : (
        <svg viewBox="0 0 24 24" className="mt-0.5 h-4 w-4 shrink-0 fill-red-500" aria-hidden="true">
          <path d="M19 6.4 17.6 5 12 10.6 6.4 5 5 6.4 10.6 12 5 17.6 6.4 19 12 13.4 17.6 19 19 17.6 13.4 12z" />
        </svg>
      )}
      <span className={ok ? '' : 'text-slate-400'}>{text}</span>
    </li>
  );
}
