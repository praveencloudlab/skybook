import { t } from '../lib/i18n';
import { useEffect, useMemo, useRef, useState } from 'react';
import { quotesApi, type TravelClass } from '../api/quotes';
import { addDaysIso, todayIso } from '../lib/format';

/**
 * The fare calendar - the premium-carrier date picker: a "Travelling when?"
 * tile opening a full-width panel of three months side by side, every date
 * with a bookable departure carrying the route's lowest per-person fare for
 * the chosen cabin beneath the day number, the selected date outlined in
 * gold, chevrons to page the window, a Reset link and the taxes note.
 *
 * <p>All of it is REAL data: availability comes from the route-calendar
 * endpoint (one call for the whole window) and the price from a live quote on
 * the route's first bookable flight - pricing is deterministic per cabin, so
 * one quote prices every date honestly. Dates without flights show no price
 * and cannot be picked.
 *
 * <p>The root is intentionally NOT positioned: the absolute panel resolves
 * against the booking widget's relative form container and spans it.
 */
export function FareCalendar({
  origin,
  destination,
  cabin,
  value,
  onChange,
  label = t('widget.when'),
}: {
  origin: string;
  destination: string;
  cabin: TravelClass;
  value: string; // yyyy-MM-dd
  onChange: (date: string) => void;
  /** Trigger caption - 'Return' for the inbound calendar. */
  label?: string;
}) {
  const [open, setOpen] = useState(false);
  // First visible month, as an offset from the current month.
  const [offset, setOffset] = useState(() => monthDiff(new Date(), new Date(`${value}T00:00:00`)));
  // date -> that day's own cheapest fare (demand-shaped: near dates pricier,
  // far dates discounted, Fri/Sun a touch more).
  const [days, setDays] = useState<Map<string, number>>(new Map());
  const [loading, setLoading] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  // Close on outside click / Escape.
  useEffect(() => {
    if (!open) {
      return;
    }
    const onDown = (event: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const months = useMemo(() => {
    const base = new Date();
    return [0, 1, 2].map((i) => new Date(base.getFullYear(), base.getMonth() + offset + i, 1));
  }, [offset]);

  // Per-date fares for the visible window - one call covers all three months,
  // priced by the same formula checkout uses, for the chosen cabin.
  useEffect(() => {
    if (!open || origin === destination) {
      return;
    }
    const controller = new AbortController();
    const start = iso(months[0]);
    const end = iso(new Date(months[2].getFullYear(), months[2].getMonth() + 1, 0));
    setLoading(true);
    quotesApi
      .fareCalendar(origin, destination, start, end, cabin, controller.signal)
      .then((list) => setDays(new Map(list.map((d) => [d.date, Number(d.minFare)]))))
      .catch(() => {})
      .finally(() => setLoading(false));
    return () => controller.abort();
  }, [open, origin, destination, cabin, months]);

  const today = todayIso();
  const minDate = addDaysIso(today, 1);

  return (
    <div ref={rootRef} className="static text-sm">
      {/* The field tile: gold calendar disc + caption + bold value. */}
      <button
        type="button"
        onClick={() => setOpen((current) => !current)}
        aria-expanded={open}
        aria-haspopup="dialog"
        className={
          'flex w-full items-center gap-3 rounded-xl border bg-white px-3 py-2 text-left outline-none transition ' +
          (open ? 'border-brand-900 ring-1 ring-brand-900' : 'border-slate-300 hover:border-slate-400')
        }
      >
        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-accent-500 text-white">
          <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true">
            <path d="M19 4h-1V2h-2v2H8V2H6v2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2zm0 16H5V10h14zM5 8V6h14v2z" />
          </svg>
        </span>
        <span className="min-w-0">
          <span className="block text-xs font-semibold text-slate-500">{label}</span>
          <span className="tabular block truncate text-[15px] font-bold text-slate-900">
            {displayDate(value)}
          </span>
        </span>
      </button>

      {open ? (
        <>
          {/* Bottom sheet on a phone - see GuestsCabinPicker for why. */}
          <div
            className="fixed inset-0 z-40 bg-slate-900/40 sm:hidden"
            aria-hidden="true"
            onClick={() => setOpen(false)}
          />
        <div
          role="dialog"
          aria-label="Choose a travel date"
          className="fixed inset-x-0 bottom-0 z-50 max-h-[85vh] overflow-y-auto rounded-t-2xl bg-white p-4 pb-[max(1rem,env(safe-area-inset-bottom))] shadow-[var(--shadow-float)] sm:absolute sm:inset-x-0 sm:bottom-auto sm:top-full sm:z-30 sm:mt-3 sm:max-h-none sm:rounded-2xl sm:p-5"
        >
          <div className="flex items-start gap-2">
            <button
              type="button"
              aria-label="Earlier months"
              disabled={offset <= 0}
              onClick={() => setOffset((o) => Math.max(0, o - 1))}
              className="mt-1 grid h-9 w-9 shrink-0 place-items-center rounded-full border border-slate-200 text-slate-500 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40"
            >
              ‹
            </button>

            <div className="grid flex-1 gap-6 md:grid-cols-3">
              {months.map((month, index) => (
                <MonthGrid
                  key={iso(month)}
                  month={month}
                  days={days}
                  minDate={minDate}
                  value={value}
                  onChange={onChange}
                  // Only the outer months page; hide inner grids on small screens.
                  className={index > 0 ? 'hidden md:block' : ''}
                />
              ))}
            </div>

            <button
              type="button"
              aria-label="Later months"
              onClick={() => setOffset((o) => o + 1)}
              className="mt-1 grid h-9 w-9 shrink-0 place-items-center rounded-full border border-slate-200 text-slate-500 transition hover:bg-slate-50"
            >
              ›
            </button>
          </div>

          <div className="mt-4 flex items-center justify-between border-t border-slate-200 pt-3 text-sm">
            <button
              type="button"
              onClick={() => {
                onChange(minDate);
                setOffset(0);
              }}
              className="font-semibold text-brand-600 hover:underline"
            >
              Reset
            </button>
            <span className="text-xs text-slate-500">
              {loading ? 'Checking availability…' : 'Fares are per guest, inclusive of taxes and surcharges.'}
            </span>
          </div>
        </div>
        </>
      ) : null}
    </div>
  );
}

function MonthGrid({
  month,
  days,
  minDate,
  value,
  onChange,
  className = '',
}: {
  month: Date;
  /** date -> that day's cheapest fare. */
  days: Map<string, number>;
  minDate: string;
  value: string;
  onChange: (date: string) => void;
  className?: string;
}) {
  const label = month.toLocaleDateString('en-GB', { month: 'short', year: 'numeric' });
  const daysInMonth = new Date(month.getFullYear(), month.getMonth() + 1, 0).getDate();
  // Monday-first column index for the 1st of the month.
  const lead = (new Date(month.getFullYear(), month.getMonth(), 1).getDay() + 6) % 7;

  const cells: Array<{ day: number; date: string } | null> = [
    ...Array.from({ length: lead }, () => null),
    ...Array.from({ length: daysInMonth }, (_, i) => ({
      day: i + 1,
      date: iso(new Date(month.getFullYear(), month.getMonth(), i + 1)),
    })),
  ];

  return (
    <div className={className}>
      <div className="text-center text-lg font-bold text-slate-900">{label}</div>
      <div className="mt-2 grid grid-cols-7 gap-y-1 text-center">
        {['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'].map((d) => (
          <div key={d} className="pb-1 text-[10px] font-bold tracking-wide text-slate-400">
            {d}
          </div>
        ))}
        {cells.map((cell, index) => {
          if (!cell) {
            return <div key={`gap-${index}`} />;
          }
          const fare = days.get(cell.date);
          const bookable = cell.date >= minDate && fare !== undefined;
          const selected = cell.date === value;
          return (
            <button
              key={cell.date}
              type="button"
              disabled={!bookable}
              onClick={() => onChange(cell.date)}
              aria-pressed={selected}
              className={
                'mx-auto flex h-11 w-11 flex-col items-center justify-center rounded-lg border text-sm transition ' +
                (selected
                  ? 'border-accent-500 bg-accent-50 font-bold text-slate-900 ring-1 ring-accent-500'
                  : bookable
                    ? 'border-transparent font-bold text-slate-900 hover:border-slate-300 hover:bg-slate-50'
                    : 'cursor-default border-transparent font-medium text-slate-300')
              }
            >
              <span className="tabular leading-tight">{cell.day}</span>
              {bookable && fare !== undefined ? (
                <span className="tabular text-[10px] font-medium leading-tight text-slate-500">
                  {Math.round(fare).toLocaleString()}
                </span>
              ) : null}
            </button>
          );
        })}
      </div>
    </div>
  );
}

function iso(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

function monthDiff(from: Date, to: Date): number {
  return Math.max(0, (to.getFullYear() - from.getFullYear()) * 12 + to.getMonth() - from.getMonth());
}

function displayDate(value: string): string {
  const date = new Date(`${value}T00:00:00`);
  return date.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' });
}
