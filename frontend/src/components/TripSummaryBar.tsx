import type { Flight } from '../api/flights';
import { dayAndMonth, time } from '../lib/format';

/**
 * The persistent trip-context bar for the booking funnel (FRONTEND_MODULE.md
 * §10.2).
 *
 * <p>Answers "where am I and what am I booking?" on every step after a flight is
 * chosen. A live search bar here would invite re-searching mid-booking and
 * discarding the trip; a bare "back" link shows nothing. So this keeps the
 * chosen flight in view (route, date, times, flight number) with a step
 * indicator and one way back - the same summarised-context pattern a real
 * metasearch keeps above the funnel.
 */
export type BookingStepId = 'fares' | 'seat' | 'review' | 'payment';

const STEPS: Array<{ id: BookingStepId; label: string }> = [
  { id: 'fares', label: 'Fare' },
  { id: 'seat', label: 'Seat' },
  { id: 'review', label: 'Review' },
  { id: 'payment', label: 'Payment' },
];

export function TripSummaryBar({
  flight,
  step,
  onBack,
  backLabel = 'Back to results',
}: {
  flight: Flight;
  step: BookingStepId;
  onBack: () => void;
  backLabel?: string;
}) {
  const currentIndex = STEPS.findIndex((s) => s.id === step);

  return (
    <div className="sticky top-14 z-10 border-b border-slate-200 bg-white/90 backdrop-blur">
      <div className="mx-auto max-w-5xl px-6 py-3">
        <button
          type="button"
          onClick={onBack}
          className="inline-flex items-center gap-1 text-sm font-medium text-slate-500 transition hover:text-brand-700"
        >
          <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true">
            <path d="M15.4 7.4 14 6l-6 6 6 6 1.4-1.4L10.8 12z" />
          </svg>
          {backLabel}
        </button>

        <div className="mt-2 flex flex-wrap items-center justify-between gap-3">
          {/* Trip context */}
          <div className="flex items-center gap-3">
            <span className="grid h-7 w-9 shrink-0 place-items-center rounded bg-brand-600 text-[10px] font-bold text-white">
              {flight.airlineCode}
            </span>
            <div className="leading-tight">
              <div className="tabular text-sm font-semibold text-slate-900">
                {flight.originAirportCode} → {flight.destinationAirportCode}
                <span className="ml-2 font-normal text-slate-400">{flight.flightNumber}</span>
              </div>
              <div className="tabular text-xs text-slate-500">
                {dayAndMonth(flight.departureTime)} · {time(flight.departureTime)}–{time(flight.arrivalTime)}
              </div>
            </div>
          </div>

          {/* Step indicator */}
          <ol className="hidden items-center gap-1.5 sm:flex">
            {STEPS.map((s, index) => {
              const state = index < currentIndex ? 'done' : index === currentIndex ? 'current' : 'todo';
              return (
                <li key={s.id} className="flex items-center gap-1.5">
                  <span
                    className={
                      'flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium ' +
                      (state === 'current'
                        ? 'bg-brand-50 text-brand-700 ring-1 ring-inset ring-brand-200'
                        : state === 'done'
                          ? 'text-emerald-700'
                          : 'text-slate-400')
                    }
                  >
                    <span
                      className={
                        'grid h-4 w-4 place-items-center rounded-full text-[9px] font-bold ' +
                        (state === 'current'
                          ? 'bg-brand-600 text-white'
                          : state === 'done'
                            ? 'bg-emerald-500 text-white'
                            : 'bg-slate-200 text-slate-500')
                      }
                    >
                      {state === 'done' ? '✓' : index + 1}
                    </span>
                    {s.label}
                  </span>
                  {index < STEPS.length - 1 ? (
                    <span className="h-px w-4 bg-slate-200" aria-hidden="true" />
                  ) : null}
                </li>
              );
            })}
          </ol>
        </div>
      </div>
    </div>
  );
}
