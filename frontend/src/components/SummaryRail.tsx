import type { Flight } from '../api/flights';
import { AIRPORTS } from '../api/flights';
import { FARE_TYPE_LABELS, TRAVEL_CLASS_LABELS, type FareType, type TravelClass } from '../api/quotes';
import type { Travellers } from './TravellersPicker';
import { dayOffset, duration, price, time } from '../lib/format';

/**
 * The right-hand Summary rail every booking step shares (carrier pattern):
 * the flight at a glance, who is travelling, any extras taken so far, and the
 * running total - so the price is never a surprise at payment. On small
 * screens it stacks above the step's content instead of floating beside it.
 */
export interface SummaryExtra {
  label: string;
  amount: number;
}

export function SummaryRail({
  flight,
  cabin,
  fare,
  currency,
  travellers,
  guestNames,
  extras = [],
  total,
}: {
  flight: Flight;
  cabin: TravelClass;
  fare: FareType;
  currency: string;
  travellers: Travellers;
  /** Filled-in guest names once the guests step has them; counts until then. */
  guestNames?: string[];
  /** Seat / bag lines taken so far. */
  extras?: SummaryExtra[];
  total: number;
}) {
  const plusDays = dayOffset(flight.departureTime, flight.arrivalTime);
  const cityFor = (code: string) => AIRPORTS.find((a) => a.code === code)?.city ?? code;

  return (
    <aside className="h-fit rounded-2xl bg-white p-5 shadow-[var(--shadow-card)] lg:sticky lg:top-20">
      <div className="flex items-baseline justify-between">
        <h2 className="text-lg font-bold text-slate-900">Summary</h2>
        <span className="text-xs font-semibold text-accent-600 underline underline-offset-4">
          Flight details
        </span>
      </div>

      {/* Flight at a glance. */}
      <div className="mt-4 border-b border-slate-100 pb-4">
        <div className="flex items-center justify-between text-xs text-slate-500">
          <span>
            {new Date(flight.departureTime).toLocaleDateString(undefined, {
              weekday: 'short',
              day: '2-digit',
              month: 'short',
            })}
          </span>
          {plusDays > 0 ? <span className="font-semibold text-slate-700">+{plusDays} day{plusDays > 1 ? 's' : ''}</span> : null}
        </div>
        <div className="mt-1.5 flex items-center gap-3">
          <div>
            <div className="tabular text-xl font-bold text-slate-900">{time(flight.departureTime)}</div>
            <div className="text-xs font-semibold text-slate-500">{flight.originAirportCode}</div>
          </div>
          <div className="flex flex-1 flex-col items-center">
            <div className="flex w-full items-center gap-1">
              <span className="h-1 w-1 rounded-full bg-slate-300" />
              <span className="relative flex-1 border-t-2 border-dotted border-slate-300">
                <svg viewBox="0 0 24 24" className="absolute -top-[9px] left-1/2 h-4 w-4 -translate-x-1/2 fill-accent-500" aria-hidden="true">
                  <path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z" transform="rotate(90 12 12)" />
                </svg>
              </span>
              <span className="h-1 w-1 rounded-full bg-slate-300" />
            </div>
            <span className="tabular mt-1 text-[10px] text-slate-400">
              {duration(flight.departureTime, flight.arrivalTime)} · Direct
            </span>
          </div>
          <div className="text-right">
            <div className="tabular text-xl font-bold text-slate-900">{time(flight.arrivalTime)}</div>
            <div className="text-xs font-semibold text-slate-500">{flight.destinationAirportCode}</div>
          </div>
        </div>
        <div className="mt-2 flex items-center gap-1.5 text-xs">
          <span className="h-2 w-2 rounded-full bg-brand-500" aria-hidden="true" />
          <span className="font-semibold text-slate-700">{TRAVEL_CLASS_LABELS[cabin]}</span>
          <span className="text-slate-500">{FARE_TYPE_LABELS[fare]}</span>
        </div>
        <p className="mt-1 text-[11px] text-slate-400">
          {cityFor(flight.originAirportCode)} to {cityFor(flight.destinationAirportCode)}
        </p>
      </div>

      {/* Guests. */}
      <div className="border-b border-slate-100 py-4">
        <h3 className="text-sm font-bold text-slate-900">Guests</h3>
        {guestNames && guestNames.some((n) => n.trim()) ? (
          <ul className="mt-1.5 space-y-0.5 text-sm text-slate-600">
            {guestNames.filter((n) => n.trim()).map((name, i) => (
              <li key={i}>{name}</li>
            ))}
          </ul>
        ) : (
          <p className="mt-1.5 text-sm text-slate-600">
            Adult ({travellers.adults})
            {travellers.children > 0 ? ` · Child (${travellers.children})` : ''}
            {travellers.infants > 0 ? ` · Infant (${travellers.infants})` : ''}
          </p>
        )}
      </div>

      {/* Extras taken so far. */}
      {extras.length > 0 ? (
        <div className="border-b border-slate-100 py-4">
          <h3 className="text-sm font-bold text-slate-900">Extras</h3>
          <ul className="mt-1.5 space-y-1 text-sm">
            {extras.map((extra, i) => (
              <li key={i} className="flex items-center justify-between gap-2 text-slate-600">
                <span className="min-w-0 truncate">{extra.label}</span>
                <span className="tabular shrink-0 font-semibold text-slate-800">
                  {extra.amount > 0 ? price(extra.amount, currency) : 'Free'}
                </span>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {/* Total. */}
      <div className="pt-4">
        <div className="flex items-baseline justify-between">
          <h3 className="text-sm font-bold text-slate-900">Fare summary</h3>
        </div>
        <div className="mt-1 flex items-baseline justify-between">
          <span className="text-sm text-slate-600">Total:</span>
          <span className="tabular text-xl font-bold text-slate-900">{price(total, currency)}</span>
        </div>
        <p className="text-[11px] text-slate-400">Including taxes</p>
      </div>
    </aside>
  );
}
