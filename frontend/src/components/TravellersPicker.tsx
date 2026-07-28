import { useEffect, useRef, useState } from 'react';

/**
 * Who is travelling - the airline-standard adults / children / infants
 * control (FRONTEND_MODULE.md §5 screen 2).
 *
 * <p>The split is not cosmetic: passenger category is derived from date of
 * birth server-side (PassengerCategory) and drives the guardian rule, so
 * asking it up front lets checkout label each passenger form and bound its
 * date-of-birth field to the right range instead of discovering a mismatch as
 * a validation error after the whole form is filled.
 *
 * <p>Limits mirror common airline policy: at least one adult (a minor cannot
 * book alone - the same rule the server enforces at cancellation), and no
 * more infants than adults (each infant travels with one).
 */
export interface Travellers {
  adults: number;
  children: number;
  infants: number;
}

export const ONE_ADULT: Travellers = { adults: 1, children: 0, infants: 0 };

export function totalTravellers(value: Travellers): number {
  return value.adults + value.children + value.infants;
}

/** "1 adult" / "2 adults · 1 child" - the button caption. */
export function travellersLabel(value: Travellers): string {
  const parts = [
    `${value.adults} adult${value.adults === 1 ? '' : 's'}`,
    value.children > 0 ? `${value.children} child${value.children === 1 ? '' : 'ren'}` : null,
    value.infants > 0 ? `${value.infants} infant${value.infants === 1 ? '' : 's'}` : null,
  ].filter(Boolean);
  return parts.join(' · ');
}

const MAX_PARTY = 9;

export function TravellersPicker({
  value,
  onChange,
  tone = 'light',
}: {
  value: Travellers;
  onChange: (value: Travellers) => void;
  /** 'dark' when the control sits on a navy band - the caption reads in white. */
  tone?: 'light' | 'dark';
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  // Close on outside click / Escape - the popover must never trap the page.
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

  function set(patch: Partial<Travellers>) {
    const next = { ...value, ...patch };
    // An infant needs an adult's lap; dropping adults drops surplus infants.
    if (next.infants > next.adults) {
      next.infants = next.adults;
    }
    onChange(next);
  }

  const total = totalTravellers(value);

  const rows: Array<{
    key: keyof Travellers;
    label: string;
    hint: string;
    min: number;
    max: number;
  }> = [
    { key: 'adults', label: 'Adults', hint: '12 years and older', min: 1, max: MAX_PARTY - value.children - value.infants },
    { key: 'children', label: 'Children', hint: '2 to 11 years', min: 0, max: MAX_PARTY - value.adults - value.infants },
    { key: 'infants', label: 'Infants', hint: 'Under 2 years', min: 0, max: Math.min(value.adults, MAX_PARTY - value.adults - value.children) },
  ];

  return (
    <div ref={rootRef} className="relative text-sm">
      <span
        className={
          'mb-1 block text-[11px] font-semibold uppercase tracking-wide ' +
          (tone === 'dark' ? 'text-white/80' : 'text-slate-500')
        }
      >
        Travellers
      </span>
      <button
        type="button"
        onClick={() => setOpen((current) => !current)}
        aria-expanded={open}
        aria-haspopup="dialog"
        className="tabular flex w-full items-center justify-between gap-2 rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-left text-sm outline-none transition focus:border-brand-500 focus:ring-4 focus:ring-brand-500/15"
      >
        <span className="truncate">{travellersLabel(value)}</span>
        <svg
          viewBox="0 0 24 24"
          className={'h-4 w-4 shrink-0 fill-slate-400 transition-transform ' + (open ? 'rotate-180' : '')}
          aria-hidden="true"
        >
          <path d="M7.4 8.6 12 13.2l4.6-4.6L18 10l-6 6-6-6z" />
        </svg>
      </button>

      {open ? (
        <div
          role="dialog"
          aria-label="Choose travellers"
          className="absolute left-0 top-full z-30 mt-2 w-72 rounded-2xl border border-slate-200 bg-white p-4 shadow-[var(--shadow-float)]"
        >
          <div className="space-y-3">
            {rows.map((row) => {
              const current = value[row.key];
              return (
                <div key={row.key} className="flex items-center justify-between gap-3">
                  <div>
                    <div className="text-sm font-semibold text-slate-800">{row.label}</div>
                    <div className="text-xs text-slate-500">{row.hint}</div>
                  </div>
                  <div className="flex items-center gap-2">
                    <Stepper
                      label={`Fewer ${row.label.toLowerCase()}`}
                      disabled={current <= row.min}
                      onClick={() => set({ [row.key]: current - 1 } as Partial<Travellers>)}
                    >
                      −
                    </Stepper>
                    <span className="tabular w-5 text-center text-sm font-bold text-slate-900">{current}</span>
                    <Stepper
                      label={`More ${row.label.toLowerCase()}`}
                      disabled={current >= row.max || total >= MAX_PARTY}
                      onClick={() => set({ [row.key]: current + 1 } as Partial<Travellers>)}
                    >
                      +
                    </Stepper>
                  </div>
                </div>
              );
            })}
          </div>

          <p className="mt-3 border-t border-slate-100 pt-2.5 text-[11px] leading-relaxed text-slate-500">
            Each infant travels with an adult. A child or infant can't travel without an adult on the
            booking.
          </p>

          <button
            type="button"
            onClick={() => setOpen(false)}
            className="mt-2 w-full rounded-full bg-slate-100 py-2 text-sm font-semibold text-slate-700 transition hover:bg-slate-200"
          >
            Done
          </button>
        </div>
      ) : null}
    </div>
  );
}

function Stepper({
  label,
  disabled,
  onClick,
  children,
}: {
  label: string;
  disabled: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      disabled={disabled}
      onClick={onClick}
      className="grid h-8 w-8 place-items-center rounded-full border border-slate-200 text-base font-semibold text-slate-700 transition hover:border-brand-300 hover:text-brand-700 disabled:cursor-not-allowed disabled:border-slate-100 disabled:text-slate-300"
    >
      {children}
    </button>
  );
}
