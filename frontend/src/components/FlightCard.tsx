import type { Flight } from '../api/flights';
import { dayOffset, duration, time } from '../lib/format';

/**
 * One flight in a results list (FRONTEND_MODULE.md §10.2 - dense, information-rich).
 *
 * <p>Laid out the way an airline actually presents a leg: times large and
 * dominant, because that is what people compare; airports beneath them; a route
 * line between with the duration riding on it and a plane at the leading edge.
 * Times are set in tabular figures so a column of departures aligns
 * digit-for-digit and can be scanned vertically.
 */
export function FlightCard({ flight, onSelect }: { flight: Flight; onSelect?: () => void }) {
  const arrivesNextDay = dayOffset(flight.departureTime, flight.arrivalTime);
  const cancelled = flight.status === 'CANCELLED';
  const delayed = flight.status === 'DELAYED';

  return (
    <article
      className={
        'group overflow-hidden rounded-2xl bg-white ring-1 ring-slate-200 transition duration-200 ease-out ' +
        'hover:-translate-y-0.5 hover:shadow-[var(--shadow-lift)] hover:ring-brand-200 ' +
        (cancelled ? 'opacity-70' : '')
      }
    >
      <div className="flex flex-wrap items-center gap-4 px-5 py-4 sm:flex-nowrap">
        {/* Carrier block: gradient monogram + flight number, its own column so
            a frequent flyer finds the airline at a glance. */}
        <div className="flex w-24 shrink-0 flex-col items-start gap-1.5">
          <span className="grid h-9 w-9 place-items-center rounded-xl bg-brand-600 text-[11px] font-bold text-white">
            {flight.airlineCode}
          </span>
          <span className="tabular text-xs font-semibold text-slate-500">{flight.flightNumber}</span>
        </div>

        <div className="min-w-[4.5rem]">
          <div className="tabular text-[26px] leading-none font-bold tracking-tight text-slate-900">
            {time(flight.departureTime)}
          </div>
          <div className="mt-1.5 text-xs font-semibold tracking-wide text-slate-500">
            {flight.originAirportCode}
          </div>
        </div>

        {/* The route line: duration pill riding on a dashed leg with a plane at
            the leading edge - reads as a flight path, not a divider. */}
        <div className="relative flex min-w-[7rem] flex-1 flex-col items-center">
          <span className="tabular rounded-full bg-slate-50 px-2.5 py-0.5 text-[11px] font-semibold text-slate-600 ring-1 ring-inset ring-slate-200">
            {duration(flight.departureTime, flight.arrivalTime)}
          </span>
          <div className="mt-1.5 flex w-full items-center gap-1">
            <span className="h-1.5 w-1.5 rounded-full bg-slate-300" />
            <span className="relative flex-1 border-t-2 border-dashed border-slate-200">
              <svg
                viewBox="0 0 24 24"
                aria-hidden="true"
                className="absolute -top-[9px] right-0 h-4 w-4 fill-brand-600 transition-transform duration-300 group-hover:translate-x-1"
              >
                <path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z" transform="rotate(90 12 12)" />
              </svg>
            </span>
          </div>
          <span className="mt-1 text-[11px] font-medium text-slate-400">Direct</span>
        </div>

        <div className="min-w-[4.5rem] text-right">
          <div className="tabular text-[26px] leading-none font-bold tracking-tight text-slate-900">
            {time(flight.arrivalTime)}
            {arrivesNextDay > 0 ? (
              // Easy to miss and expensive to get wrong - someone booking a
              // long-haul needs to know they land the next day.
              <sup className="ml-0.5 text-xs font-semibold text-accent-600">+{arrivesNextDay}</sup>
            ) : null}
          </div>
          <div className="mt-1.5 text-xs font-semibold tracking-wide text-slate-500">
            {flight.destinationAirportCode}
          </div>
        </div>

        {/* Status + action share the trailing column. */}
        <div className="flex w-full items-center justify-between gap-3 border-t border-slate-100 pt-3 sm:w-auto sm:flex-col sm:items-end sm:justify-center sm:border-0 sm:pt-0">
          <span
            className={
              'rounded-full px-2.5 py-1 text-[11px] font-semibold ring-1 ring-inset ' +
              (cancelled
                ? 'bg-red-50 text-red-700 ring-red-200'
                : delayed
                  ? 'bg-amber-50 text-amber-800 ring-amber-200'
                  : 'bg-emerald-50 text-emerald-700 ring-emerald-200')
            }
          >
            {cancelled ? 'Cancelled' : delayed ? 'Delayed' : 'On time'}
          </span>

          {onSelect ? (
            <button
              type="button"
              onClick={onSelect}
              disabled={cancelled}
              className="inline-flex items-center gap-1.5 rounded-xl bg-brand-600 px-5 py-2 text-sm font-bold text-white transition-colors hover:bg-brand-700 focus-visible:ring-2 focus-visible:ring-brand-500/50 focus-visible:outline-none disabled:cursor-not-allowed disabled:bg-slate-300"
            >
              Select
              <svg viewBox="0 0 24 24" className="h-3.5 w-3.5 fill-current transition-transform duration-200 group-hover:translate-x-0.5" aria-hidden="true">
                <path d="M12 4l-1.4 1.4L16.2 11H4v2h12.2l-5.6 5.6L12 20l8-8z" />
              </svg>
            </button>
          ) : null}
        </div>
      </div>
    </article>
  );
}
