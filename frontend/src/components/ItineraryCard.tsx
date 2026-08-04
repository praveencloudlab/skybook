import type { Flight, Itinerary } from '../api/flights';
import { AIRPORTS } from '../api/flights';
import { dayOffset, durationFromMinutes, price, time } from '../lib/format';
import { AirlineLogo } from './AirlineLogo';

/**
 * One trip option in the results (metasearch presentation): every leg spelled
 * out - times, airports, flight number, leg duration - and between legs an
 * explicit layover strip with the airport and the WAITING time, so a
 * connection is never mistaken for a through-ticket.
 *
 * Layout: legs on the left; a ticket-style right rail carries the total
 * durationFromMinutes, the "from" price and ONE gold CTA - except a mixed-carrier
 * self-transfer, whose per-leg Book buttons stay on the rows because each
 * leg genuinely is its own ticket.
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
  const selfTransfer = stops > 0 && !throughTicket;
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

  const badge =
    stops === 0 ? (
      <span className="rounded-full bg-emerald-100 px-2.5 py-0.5 text-[11px] font-bold text-emerald-700">
        Direct
      </span>
    ) : (
      <span
        className={
          'rounded-full px-2.5 py-0.5 text-[11px] font-bold ' +
          (throughTicket ? 'bg-emerald-100 text-emerald-700' : 'bg-amber-100 text-amber-800')
        }
      >
        {stops === 1 ? '1 stop' : `${stops} stops`}
      </span>
    );

  return (
    <article className="group flex flex-col overflow-hidden rounded-2xl bg-white ring-1 ring-slate-200 transition duration-200 hover:shadow-[var(--shadow-lift)] hover:ring-slate-300 sm:flex-row">
      {/* Legs column. */}
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2 border-b border-slate-100 bg-slate-50/70 px-5 py-2.5">
          {badge}
          {throughTicket ? (
            <span className="text-[11px] font-semibold text-emerald-700">
              Through-ticket · bags checked through · one booking
            </span>
          ) : null}
          {selfTransfer ? (
            <span className="text-[11px] font-semibold text-slate-500">
              Self-transfer · booked as {legs.length} tickets
            </span>
          ) : null}
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
                    {durationFromMinutes(leg.durationMinutes)}
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
                {/* Only a self-transfer books per leg - each row is its own ticket. */}
                {selfTransfer && onSelectLeg ? (
                  <button
                    type="button"
                    onClick={() => onSelectLeg(leg)}
                    disabled={leg.status === 'CANCELLED'}
                    className="shrink-0 rounded-full border border-accent-500 px-3.5 py-1.5 text-xs font-bold text-accent-600 transition hover:bg-accent-500 hover:text-white disabled:cursor-not-allowed disabled:border-slate-300 disabled:text-slate-300"
                  >
                    Book leg {index + 1}
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
      </div>

      {/* Ticket rail: durationFromMinutes, price, ONE clear action. Perforated edge on
          desktop, a bottom bar on mobile. */}
      <div className="flex items-center justify-between gap-3 border-t border-dashed border-slate-200 bg-slate-50/60 px-5 py-3 sm:w-44 sm:flex-col sm:items-stretch sm:justify-center sm:border-t-0 sm:border-l sm:px-4 sm:py-4 sm:text-center">
        <div>
          <div className="tabular text-[11px] font-semibold text-slate-500">
            {fmt(totalDurationMinutes)}
            {plusDays > 0 ? (
              <span className="ml-1 text-accent-600">+{plusDays} day{plusDays > 1 ? 's' : ''}</span>
            ) : null}
          </div>
          {fromFare !== undefined ? (
            <div className="mt-0.5">
              <span className="text-[11px] font-medium text-slate-400">from </span>
              <span className="tabular text-xl font-extrabold tracking-tight text-slate-900">
                {price(fromFare, 'GBP')}
              </span>
              <div className="text-[10px] font-medium text-slate-400">per person</div>
            </div>
          ) : null}
        </div>
        {!selfTransfer && (onSelectLeg || onSelectItinerary) ? (
          <button
            type="button"
            onClick={() => (throughTicket ? onSelectItinerary?.(legs) : onSelectLeg?.(first))}
            disabled={legs.some((l) => l.status === 'CANCELLED')}
            className="shrink-0 rounded-full bg-accent-500 px-6 py-2 text-sm font-bold text-white shadow-sm transition group-hover:shadow hover:bg-accent-600 disabled:cursor-not-allowed disabled:bg-slate-300 sm:mt-3 sm:w-full"
          >
            Select
          </button>
        ) : (
          <span className="text-[10px] font-medium leading-tight text-slate-400 sm:mt-3">
            Book each leg from its row
          </span>
        )}
      </div>
    </article>
  );
}
