import type { Flight } from '../api/flights';
import { dayOffset, duration, time } from '../lib/format';

/**
 * One flight in a results list, laid out the way the premium carriers lay out
 * a leg row: a sand aircraft chip above the departure, big bold times with the
 * airport code RIGHT BESIDE them (21:45 LHR ---✈--- BKK 18:10⁺¹), the duration
 * under the dashed leg, and the action column on the right. Times are set in
 * tabular figures so a column of departures aligns digit-for-digit.
 */
export function FlightCard({ flight, onSelect }: { flight: Flight; onSelect?: () => void }) {
  const arrivesNextDay = dayOffset(flight.departureTime, flight.arrivalTime);
  const cancelled = flight.status === 'CANCELLED';
  const delayed = flight.status === 'DELAYED';

  return (
    <article
      className={
        'overflow-hidden rounded-2xl bg-white shadow-[var(--shadow-card)] transition duration-200 ease-out hover:shadow-[var(--shadow-lift)] ' +
        (cancelled ? 'opacity-70' : '')
      }
    >
      <div className="flex flex-wrap items-center gap-x-6 gap-y-3 px-5 py-4 sm:flex-nowrap">
        <div className="min-w-0 flex-1">
          {/* The sand aircraft chip - the carrier's signature little tag. */}
          <span className="tabular inline-block rounded-md bg-accent-100 px-2 py-0.5 text-xs font-bold text-accent-700">
            {flight.airlineCode} · {flight.flightNumber}
          </span>

          <div className="mt-2 flex items-center gap-3">
            <div className="flex items-baseline gap-1.5">
              <span className="tabular text-[26px] leading-none font-bold tracking-tight text-slate-900">
                {time(flight.departureTime)}
              </span>
              <span className="text-sm font-bold text-slate-500">{flight.originAirportCode}</span>
            </div>

            {/* Dashed leg with the aircraft riding its centre. */}
            <div className="relative min-w-[6rem] flex-1">
              <div className="flex items-center">
                <span className="h-1.5 w-1.5 rounded-full border border-slate-400 bg-white" />
                <span className="flex-1 border-t-2 border-dashed border-slate-300" />
                <svg viewBox="0 0 24 24" className="mx-1 h-4 w-4 shrink-0 fill-brand-900" aria-hidden="true">
                  <path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z" transform="rotate(90 12 12)" />
                </svg>
                <span className="flex-1 border-t-2 border-dashed border-slate-300" />
                <span className="h-1.5 w-1.5 rounded-full border border-slate-400 bg-white" />
              </div>
              <div className="tabular mt-1 text-center text-xs font-medium text-slate-500">
                Direct · {duration(flight.departureTime, flight.arrivalTime)}
              </div>
            </div>

            <div className="flex items-baseline gap-1.5">
              <span className="text-sm font-bold text-slate-500">{flight.destinationAirportCode}</span>
              <span className="tabular text-[26px] leading-none font-bold tracking-tight text-slate-900">
                {time(flight.arrivalTime)}
                {arrivesNextDay > 0 ? (
                  // Easy to miss and expensive to get wrong - someone booking a
                  // long-haul needs to know they land the next day.
                  <sup className="ml-0.5 text-xs font-bold text-accent-600">+{arrivesNextDay}</sup>
                ) : null}
              </span>
            </div>
          </div>
        </div>

        {/* Status + action column. */}
        <div className="flex w-full items-center justify-between gap-3 border-t border-slate-100 pt-3 sm:w-auto sm:flex-col sm:items-end sm:justify-center sm:border-0 sm:pt-0">
          <span
            className={
              'rounded-full px-2.5 py-1 text-[11px] font-bold ' +
              (cancelled
                ? 'bg-red-50 text-red-700'
                : delayed
                  ? 'bg-amber-50 text-amber-800'
                  : 'bg-emerald-50 text-emerald-700')
            }
          >
            {cancelled ? 'Cancelled' : delayed ? 'Delayed' : 'On time'}
          </span>

          {onSelect ? (
            <button
              type="button"
              onClick={onSelect}
              disabled={cancelled}
              className="rounded-full bg-accent-500 px-6 py-2 text-sm font-bold text-white transition-colors hover:bg-accent-600 focus-visible:ring-2 focus-visible:ring-accent-500/60 focus-visible:outline-none disabled:cursor-not-allowed disabled:bg-accent-200"
            >
              Select
            </button>
          ) : null}
        </div>
      </div>
    </article>
  );
}
