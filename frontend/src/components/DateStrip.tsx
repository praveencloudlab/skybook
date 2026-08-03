import { useEffect, useMemo, useState } from 'react';
import { quotesApi, type TravelClass } from '../api/quotes';
import { addDaysIso, price, todayIso } from '../lib/format';

/**
 * The date-price strip above search results (carrier pattern): a pageable run
 * of days around the searched date, each carrying the route's cheapest
 * per-guest fare for the chosen cabin, the selected day as a dark tile.
 * Prices come from the same fare-calendar endpoint (same formula checkout
 * uses), so switching days never shows a number booking won't honour.
 */
const WINDOW = 7;

export function DateStrip({
  origin,
  destination,
  date,
  cabin,
  currency = 'GBP',
  onPickDate,
}: {
  origin: string;
  destination: string;
  /** The currently searched (selected) date, yyyy-MM-dd. */
  date: string;
  cabin: TravelClass;
  currency?: string;
  onPickDate: (date: string) => void;
}) {
  // Window starts 3 days before the selected date; chevrons page by a week.
  const [start, setStart] = useState(() => addDaysIso(date, -3));
  const [fares, setFares] = useState<Map<string, number>>(new Map());

  useEffect(() => {
    setStart(addDaysIso(date, -3));
  }, [date]);

  const days = useMemo(
    () => Array.from({ length: WINDOW }, (_, i) => addDaysIso(start, i)),
    [start],
  );

  useEffect(() => {
    const controller = new AbortController();
    quotesApi
      .fareCalendar(origin, destination, days[0], days[days.length - 1], cabin, controller.signal)
      .then((list) => setFares(new Map(list.map((d) => [d.date, Number(d.minFare)]))))
      .catch(() => {});
    return () => controller.abort();
  }, [origin, destination, cabin, days]);

  const label = (iso: string) => {
    const d = new Date(`${iso}T00:00:00`);
    return `${d.getDate()}, ${d.toLocaleDateString(undefined, { month: 'short' })}`;
  };

  return (
    <div className="flex items-center gap-1 rounded-2xl bg-white px-2 py-2 shadow-[var(--shadow-card)]">
      <button
        type="button"
        aria-label="Earlier dates"
        // Clamp at today: there is nothing bookable behind it, and the
        // backend calendar no longer prices past days anyway.
        disabled={days[0] <= todayIso()}
        onClick={() => setStart((s) => addDaysIso(s, -WINDOW))}
        className="grid h-9 w-9 shrink-0 place-items-center rounded-full text-slate-500 transition hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-30"
      >
        ‹
      </button>
      <div className="grid flex-1 grid-cols-4 gap-1 sm:grid-cols-7">
        {days.map((day, index) => {
          const fare = fares.get(day);
          const selected = day === date;
          return (
            <button
              key={day}
              type="button"
              disabled={fare === undefined}
              onClick={() => onPickDate(day)}
              aria-pressed={selected}
              className={
                'flex flex-col items-center rounded-xl px-2 py-2 text-center transition ' +
                (selected
                  ? 'bg-brand-950 text-white'
                  : fare === undefined
                    ? 'cursor-not-allowed text-slate-300'
                    : 'text-slate-700 hover:bg-slate-100') +
                (index >= 4 ? ' hidden sm:flex' : '')
              }
            >
              <span className="tabular text-xs">{label(day)}</span>
              <span className={'tabular text-sm font-bold ' + (selected ? '' : 'text-slate-900')}>
                {fare !== undefined ? price(fare, currency).replace('.00', '') : '—'}
              </span>
            </button>
          );
        })}
      </div>
      <button
        type="button"
        aria-label="Later dates"
        onClick={() => setStart((s) => addDaysIso(s, WINDOW))}
        className="grid h-9 w-9 shrink-0 place-items-center rounded-full text-slate-500 transition hover:bg-slate-100"
      >
        ›
      </button>
    </div>
  );
}
