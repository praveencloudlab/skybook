import type { Flight, Itinerary } from '../api/flights';
import { AIRPORTS } from '../api/flights';
import { dayOffset, duration, price, time } from '../lib/format';
import { AirlineLogo } from './AirlineLogo';

/**
 * One trip option in the results (metasearch presentation): every leg spelled
 * out - times, airports, flight number, leg duration - and between legs an
 * explicit layover strip with the airport and the WAITING time, so a
 * connection is never mistaken for a through-ticket. Direct itineraries book
 * as one ticket; connections are self-transfer and book one ticket per leg.
 */
export function ItineraryCard({
  itinerary,
  fareForDate,
  onSelectLeg,
  onSelectItinerary,
}: {
  itinerary: Itinerary;
  /**
   * Lowest per-person fare (GBP) for a departure day in the searched cabin -
   * the same deterministic floor the date strip and quote page price from,
   * so the card can never disagree with checkout. A connection's price is
   * the sum of its legs (each leg is its own ticket).
   */
  fareForDate?: (isoDate: string) => number | undefined;
  /** Book a leg (direct = the only leg; self-transfer = one ticket per leg). */
  onSelectLeg?: (flight: Flight) => void;
  /** Book a same-carrier connection as ONE through-ticket (all legs, one booking). */
  onSelectItinerary?: (legs: Flight[]) => void;
}) {
  const { legs, stops, totalDurationMinutes, layoverMinutes } = itinerary;
  // A same-carrier connection sells as one protected through-ticket; mixed
  // carriers are the self-transfer combination the search engine assembled.
  const throughTicket = stops > 0 && itinerary.sameCarrier && onSelectItinerary !== undefined;
  const first = legs[0];
  const last = legs[legs.length - 1];
  const plusDays = dayOffset(first.departureTime, last.arrivalTime);
  const cityFor = (code: string) => AIRPORTS.find((a) => a.code === code)?.city ?? code;
  const fmt = (mins: number) => `${Math.floor(mins / 60)}h ${String(mins % 60).padStart(2, '0')}m`;

  // Sum the legs' day-floor fares; any unknown leg means no honest total,
  // so show nothing rather than a wrong number.
  const legFares = fareForDate ? legs.map((leg) => fareForDate(leg.departureTime.slice(0, 10))) : [];
  const fromFare =
    legFares.length && legFares.every((f) => f !== undefined)
      ? (legFares as number[]).reduce((sum, f) => sum + f, 0)
      : undefined;

  return (
    <article className="overflow-hidden rounded-2xl bg-white ring-1 ring-slate-200 transition duration-200 hover:shadow-[var(--shadow-lift)]">
      {/* Header: stops badge + totals. */}
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 bg-slate-50/70 px-5 py-2.5">
        <div className="flex items-center gap-2">
          <span
            className={
              'rounded-full px-2.5 py-0.5 text-[11px] font-bold ' +
              (stops === 0
                ? 'bg-emerald-100 text-emerald-700'
                : 'bg-amber-100 text-amber-800')
            }
          >
            {stops === 0 ? 'Direct' : stops === 1 ? '1 stop' : `${stops} stops`}
          </span>
          {stops > 0 ? (
            throughTicket ? (
              <span className="text-[11px] font-semibold text-emerald-700">
                Through-ticket · bags checked through · one booking
              </span>
            ) : (
              <span className="text-[11px] font-semibold text-slate-500">
                Self-transfer · booked as {legs.length} tickets
              </span>
            )
          ) : null}
        </div>
        <span className="flex items-baseline gap-3">
          {fromFare !== undefined ? (
            <span className="tabular text-sm font-bold text-slate-900">
              <span className="mr-1 text-[11px] font-semibold text-slate-500">from</span>
              {price(fromFare, 'GBP')}
              <span className="ml-1 text-[11px] font-medium text-slate-400">pp</span>
            </span>
          ) : null}
          <span className="tabular text-xs font-semibold text-slate-600">
            Total {fmt(totalDurationMinutes)}
            {plusDays > 0 ? <span className="ml-1 text-accent-600">+{plusDays} day{plusDays > 1 ? 's' : ''}</span> : null}
          </span>
        </span>
      </div>

      <div className="px-5 py-3">
        {legs.map((leg, index) => (
          <div key={leg.id}>
            {/* Leg row. */}
            <div className="flex items-center gap-4 py-2.5">
              <AirlineLogo code={leg.airlineCode} />
              <div className="min-w-[4.2rem]">
                <div className="tabular text-xl font-bold leading-none text-slate-900">{time(leg.departureTime)}</div>
                <div className="mt-1 text-xs font-semibold text-slate-500">{leg.originAirportCode}</div>
              </div>
              <div className="flex min-w-[5rem] flex-1 flex-col items-center">
                <span className="tabular text-[10px] font-semibold text-slate-500">
                  {duration(leg.departureTime, leg.arrivalTime)}
                </span>
                <div className="mt-1 flex w-full items-center gap-1">
                  <span className="h-1 w-1 rounded-full bg-slate-300" />
                  <span className="relative flex-1 border-t-2 border-dashed border-slate-200">
                    <svg viewBox="0 0 24 24" aria-hidden="true" className="absolute -top-[8px] left-1/2 h-3.5 w-3.5 -translate-x-1/2 fill-accent-500">
                      <path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z" transform="rotate(90 12 12)" />
                    </svg>
                  </span>
                  <span className="h-1 w-1 rounded-full bg-slate-300" />
                </div>
                <span className="tabular mt-0.5 text-[10px] text-slate-400">{leg.flightNumber}</span>
              </div>
              <div className="min-w-[4.2rem] text-right">
                <div className="tabular text-xl font-bold leading-none text-slate-900">
                  {time(leg.arrivalTime)}
                  {dayOffset(leg.departureTime, leg.arrivalTime) > 0 ? (
                    <sup className="ml-0.5 text-[10px] font-bold text-accent-600">
                      +{dayOffset(leg.departureTime, leg.arrivalTime)}
                    </sup>
                  ) : null}
                </div>
                <div className="mt-1 text-xs font-semibold text-slate-500">{leg.destinationAirportCode}</div>
              </div>
              {throughTicket ? (
                // One booking covers every leg: a single Select on the first
                // row, a spacer on the rest so the rows stay aligned.
                index === 0 ? (
                  <button
                    type="button"
                    onClick={() => onSelectItinerary(legs)}
                    disabled={legs.some((l) => l.status === 'CANCELLED')}
                    className="shrink-0 rounded-full bg-accent-500 px-4 py-1.5 text-xs font-bold text-white transition hover:bg-accent-600 disabled:cursor-not-allowed disabled:bg-slate-300"
                  >
                    Select
                  </button>
                ) : (
                  <span className="w-[4.5rem] shrink-0" aria-hidden="true" />
                )
              ) : onSelectLeg ? (
                <button
                  type="button"
                  onClick={() => onSelectLeg(leg)}
                  disabled={leg.status === 'CANCELLED'}
                  className="shrink-0 rounded-full bg-accent-500 px-4 py-1.5 text-xs font-bold text-white transition hover:bg-accent-600 disabled:cursor-not-allowed disabled:bg-slate-300"
                >
                  {stops === 0 ? 'Select' : `Book leg ${index + 1}`}
                </button>
              ) : null}
            </div>

            {/* Layover strip - the WAIT, made unmissable. */}
            {index < legs.length - 1 ? (
              <div
                className={
                  'my-1 flex items-center gap-2 rounded-xl px-3.5 py-2 text-xs ring-1 ring-inset ' +
                  (throughTicket
                    ? 'bg-emerald-50 text-emerald-900 ring-emerald-200'
                    : 'bg-amber-50 text-amber-900 ring-amber-200')
                }
              >
                <svg
                  viewBox="0 0 24 24"
                  className={'h-3.5 w-3.5 shrink-0 ' + (throughTicket ? 'fill-emerald-600' : 'fill-amber-600')}
                  aria-hidden="true"
                >
                  <path d="M12 2a10 10 0 1 0 10 10A10 10 0 0 0 12 2zm4.2 13.2-5-3V6h1.6v5.3l4.2 2.5z" />
                </svg>
                <span>
                  <span className="font-bold">
                    Layover in {cityFor(leg.destinationAirportCode)} ({leg.destinationAirportCode})
                  </span>
                  {' · '}
                  <span className="tabular font-bold">{fmt(layoverMinutes[index] ?? 0)}</span> waiting time ·{' '}
                  {throughTicket
                    ? 'connection protected, bags checked through'
                    : 'collect and re-check your bags (self-transfer)'}
                </span>
              </div>
            ) : null}
          </div>
        ))}
      </div>
    </article>
  );
}
