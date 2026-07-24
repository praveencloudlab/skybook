import type { Flight } from '../api/flights';
import { dayOffset, duration, time } from '../lib/format';

/**
 * One flight in a results list (FRONTEND_MODULE.md §10.2 - dense, information-rich).
 *
 * <p>Laid out the way an airline actually presents a leg: times large and
 * dominant, because that is what people compare; airports beneath them; a route
 * line between with the duration on it. Times are set in tabular figures so a
 * column of departures aligns digit-for-digit and can be scanned vertically.
 */
export function FlightCard({ flight, onSelect }: { flight: Flight; onSelect?: () => void }) {
  const arrivesNextDay = dayOffset(flight.departureTime, flight.arrivalTime);
  const cancelled = flight.status === 'CANCELLED';
  const delayed = flight.status === 'DELAYED';

  return (
    <article
      className={
        'card card-hover overflow-hidden ' + (cancelled ? 'opacity-70' : '')
      }
    >
      {/* Carrier strip: the airline code is the first thing a frequent flyer
          looks for, so it gets its own band rather than being lost in a row. */}
      <div className="flex items-center justify-between border-b border-slate-100 bg-slate-50/70 px-4 py-2">
        <div className="flex items-center gap-2">
          <span className="grid h-6 w-6 place-items-center rounded bg-brand-600 text-[10px] font-bold text-white">
            {flight.airlineCode}
          </span>
          <span className="tabular text-xs font-medium text-slate-600">{flight.flightNumber}</span>
        </div>

        {cancelled || delayed ? (
          <span
            className={
              'rounded-full px-2 py-0.5 text-[11px] font-semibold ring-1 ring-inset ' +
              (cancelled
                ? 'bg-red-50 text-red-700 ring-red-200'
                : 'bg-amber-50 text-amber-800 ring-amber-200')
            }
          >
            {cancelled ? 'Cancelled' : 'Delayed'}
          </span>
        ) : (
          <span className="text-[11px] font-medium text-emerald-700">On time</span>
        )}
      </div>

      <div className="flex items-center gap-4 px-4 py-4">
        <div className="min-w-[4.5rem]">
          <div className="tabular text-2xl leading-none font-semibold tracking-tight text-slate-900">
            {time(flight.departureTime)}
          </div>
          <div className="mt-1 text-xs font-medium tracking-wide text-slate-500">
            {flight.originAirportCode}
          </div>
        </div>

        {/* The route line: a dashed leg with a dot at the destination end, which
            reads as a flight path rather than a divider. */}
        <div className="flex flex-1 flex-col items-center gap-1">
          <span className="tabular text-[11px] font-medium text-slate-500">
            {duration(flight.departureTime, flight.arrivalTime)}
          </span>
          <div className="flex w-full items-center gap-1">
            <span className="h-1.5 w-1.5 rounded-full bg-slate-300" />
            <span className="route-line" />
          </div>
          <span className="text-[11px] text-slate-400">Direct</span>
        </div>

        <div className="min-w-[4.5rem] text-right">
          <div className="tabular text-2xl leading-none font-semibold tracking-tight text-slate-900">
            {time(flight.arrivalTime)}
            {arrivesNextDay > 0 ? (
              // Easy to miss and expensive to get wrong - someone booking a
              // long-haul needs to know they land the next day.
              <sup className="ml-0.5 text-xs font-semibold text-accent-600">+{arrivesNextDay}</sup>
            ) : null}
          </div>
          <div className="mt-1 text-xs font-medium tracking-wide text-slate-500">
            {flight.destinationAirportCode}
          </div>
        </div>
      </div>

      {onSelect ? (
        <div className="flex justify-end border-t border-slate-100 bg-slate-50/50 px-4 py-2.5">
          <button
            type="button"
            onClick={onSelect}
            disabled={cancelled}
            className="rounded-md bg-brand-600 px-4 py-1.5 text-sm font-medium text-white shadow-sm transition hover:bg-brand-700 focus-visible:ring-2 focus-visible:ring-brand-500/50 focus-visible:outline-none disabled:cursor-not-allowed disabled:bg-slate-300 disabled:shadow-none"
          >
            Select flight
          </button>
        </div>
      ) : null}
    </article>
  );
}
