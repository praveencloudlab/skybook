import type { Flight } from '../api/flights';
import { dayOffset, duration, time } from '../lib/format';

/**
 * One flight in a results list (FRONTEND_MODULE.md §10.2 - dense, information-rich).
 *
 * <p>Laid out the way an airline actually presents a leg: times large and
 * dominant, because that is what people compare; airports beneath them; duration
 * on the connecting line. Times are set in tabular figures so a column of
 * departures aligns digit-for-digit and can be scanned vertically.
 */
export function FlightCard({ flight, onSelect }: { flight: Flight; onSelect?: () => void }) {
  const arrivesNextDay = dayOffset(flight.departureTime, flight.arrivalTime);
  const disrupted = flight.status === 'CANCELLED' || flight.status === 'DELAYED';

  return (
    <article className="rounded border border-slate-200 bg-white p-4 transition hover:border-brand-300">
      <div className="flex items-start justify-between gap-4">
        <div className="flex items-baseline gap-2 text-xs text-slate-500">
          <span className="font-semibold text-slate-700">{flight.airlineCode}</span>
          <span className="tabular">{flight.flightNumber}</span>
        </div>
        {disrupted ? (
          <span
            className={
              'rounded px-1.5 py-0.5 text-xs font-medium ' +
              (flight.status === 'CANCELLED'
                ? 'bg-red-50 text-red-700'
                : 'bg-amber-50 text-amber-800')
            }
          >
            {flight.status === 'CANCELLED' ? 'Cancelled' : 'Delayed'}
          </span>
        ) : null}
      </div>

      <div className="mt-3 flex items-center gap-4">
        <div>
          <div className="tabular text-2xl leading-none font-semibold text-slate-900">
            {time(flight.departureTime)}
          </div>
          <div className="mt-1 text-xs text-slate-500">{flight.originAirportCode}</div>
        </div>

        <div className="flex-1 text-center">
          <div className="text-xs text-slate-500">{duration(flight.departureTime, flight.arrivalTime)}</div>
          <div className="my-1 h-px bg-slate-200" />
          <div className="text-xs text-slate-400">Direct</div>
        </div>

        <div className="text-right">
          <div className="tabular text-2xl leading-none font-semibold text-slate-900">
            {time(flight.arrivalTime)}
            {arrivesNextDay > 0 ? (
              // Easy to miss and expensive to get wrong - someone booking a
              // long-haul needs to know they land the next day.
              <sup className="ml-0.5 text-xs font-medium text-brand-700">+{arrivesNextDay}</sup>
            ) : null}
          </div>
          <div className="mt-1 text-xs text-slate-500">{flight.destinationAirportCode}</div>
        </div>
      </div>

      {onSelect ? (
        <div className="mt-4 flex justify-end">
          <button
            type="button"
            onClick={onSelect}
            disabled={flight.status === 'CANCELLED'}
            className="rounded bg-brand-600 px-3 py-1.5 text-sm font-medium text-white transition hover:bg-brand-700 disabled:cursor-not-allowed disabled:bg-slate-300"
          >
            Select
          </button>
        </div>
      ) : null}
    </article>
  );
}
