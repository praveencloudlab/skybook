import { time } from '../lib/format';
import type { Flight } from '../api/flights';

/**
 * The carrier-style booking progress bar: a dark teal band with the journey's
 * stages as icon chips joined by dotted runs, a green tick stamping each
 * completed stage. Rendered at the top of every step from fares to payment so
 * the traveller always knows where they are and what remains.
 */
export type BookingStage = 'flights' | 'guests' | 'seats' | 'bags' | 'payment';

const STAGES: Array<{ id: BookingStage; label: string; icon: string }> = [
  { id: 'flights', label: 'Flights', icon: 'M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z' },
  { id: 'guests', label: 'Guests', icon: 'M12 12a4 4 0 1 0-4-4 4 4 0 0 0 4 4zm0 2c-3.3 0-7 1.7-7 4v2h14v-2c0-2.3-3.7-4-7-4z' },
  { id: 'seats', label: 'Seats', icon: 'M7 5a2 2 0 0 1 4 0v6h6a2 2 0 0 1 2 2v3h-2v-3H7a2 2 0 0 1-2-2V5h2zm-2 11h12v2H7a2 2 0 0 1-2-2z' },
  { id: 'bags', label: 'Bags', icon: 'M9 6V4a3 3 0 0 1 6 0v2h2a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h2zm2 0h2V4a1 1 0 0 0-2 0v2z' },
  { id: 'payment', label: 'Payment', icon: 'M3 6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v2H3V6zm0 4h18v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-8zm3 4h5v2H6v-2z' },
];

export function BookingStepper({
  current,
  flight,
  route,
  onModify,
}: {
  current: BookingStage;
  flight?: Flight | null;
  /** "London → Dubai"-style route caption for the summary strip. */
  route?: string;
  onModify?: () => void;
}) {
  const currentIndex = STAGES.findIndex((s) => s.id === current);

  return (
    <div className="bg-brand-950 text-white">
      {/* Trip summary strip. */}
      {flight ? (
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-3 gap-y-1 px-6 pt-3 text-sm text-white/85">
          {route ? <span className="font-semibold text-white">{route}</span> : null}
          <span className="hidden text-white/30 sm:inline">|</span>
          <span className="tabular">
            {new Date(flight.departureTime).toLocaleDateString(undefined, { weekday: 'short', day: '2-digit', month: 'short' })}
            {' · '}
            {time(flight.departureTime)}
          </span>
          {onModify ? (
            <button
              type="button"
              onClick={onModify}
              className="inline-flex items-center gap-1 rounded-full border border-white/25 px-3 py-0.5 font-semibold text-white/85 transition hover:bg-white/10"
            >
              ← Back
            </button>
          ) : null}
        </div>
      ) : null}

      {/* Stages. */}
      <div className="mx-auto flex max-w-6xl items-center justify-center gap-1 overflow-x-auto px-6 py-3 sm:gap-2">
        {STAGES.map((stage, index) => {
          const done = index < currentIndex;
          const active = index === currentIndex;
          return (
            <div key={stage.id} className="flex items-center gap-1 sm:gap-2">
              {index > 0 ? (
                <span className="tracking-[0.3em] text-white/30" aria-hidden="true">
                  ······
                </span>
              ) : null}
              <span
                className={
                  'relative flex items-center gap-1.5 rounded-full px-2 py-1 text-xs font-semibold sm:text-sm ' +
                  (active ? 'text-white' : done ? 'text-white/80' : 'text-white/45')
                }
              >
                <span
                  className={
                    'relative grid h-7 w-7 shrink-0 place-items-center rounded-full ' +
                    (active ? 'bg-accent-500' : done ? 'bg-white/15' : 'bg-white/10')
                  }
                >
                  <svg viewBox="0 0 24 24" className="h-3.5 w-3.5 fill-current" aria-hidden="true">
                    <path d={stage.icon} />
                  </svg>
                  {done ? (
                    <span className="absolute -right-0.5 -top-0.5 grid h-3 w-3 place-items-center rounded-full bg-emerald-400 text-brand-950">
                      <svg viewBox="0 0 24 24" className="h-2 w-2 fill-current" aria-hidden="true">
                        <path d="M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z" />
                      </svg>
                    </span>
                  ) : null}
                </span>
                <span className="hidden sm:inline">{stage.label}</span>
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
