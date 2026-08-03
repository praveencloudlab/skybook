import { useEffect, useRef, useState } from 'react';

/**
 * Who is travelling - the airline-standard adults / children / infants
 * control (FRONTEND_MODULE.md §5 screen 2), styled as the premium-carrier
 * "Guests" widget: an outlined field tile with a gold icon disc, opening a
 * panel of grey guest rows with square steppers (grey minus, deep-teal plus,
 * boxed count) and a bordered Continue pill - the etihad.com booking panel,
 * component for component.
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

/** "1 adult" / "2 adults · 1 child" - used by checkout summaries. */
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
}: {
  value: Travellers;
  onChange: (value: Travellers) => void;
  /** Kept for call-site compatibility; captions now live inside the tile. */
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
    { key: 'adults', label: 'Adults', hint: 'Age 12+', min: 1, max: MAX_PARTY - value.children - value.infants },
    { key: 'children', label: 'Children', hint: 'Age 2 — 11 years', min: 0, max: MAX_PARTY - value.adults - value.infants },
    { key: 'infants', label: 'Infants', hint: 'Under 2 years', min: 0, max: Math.min(value.adults, MAX_PARTY - value.adults - value.children) },
  ];

  return (
    <div ref={rootRef} className="relative text-sm">
      {/* The field tile: gold icon disc + caption + bold value. */}
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
            <path d="M12 12a4 4 0 1 0-4-4 4 4 0 0 0 4 4zm0 2c-3.3 0-7 1.7-7 4v2h14v-2c0-2.3-3.7-4-7-4z" />
          </svg>
        </span>
        <span className="min-w-0">
          <span className="block text-xs font-semibold text-slate-500">Guests</span>
          <span className="tabular block truncate text-[15px] font-bold text-slate-900">
            {total} Guest{total === 1 ? '' : 's'}
          </span>
        </span>
      </button>

      {open ? (
        <div
          role="dialog"
          aria-label="Choose guests"
          className="absolute left-0 top-full z-30 mt-3 w-[22rem] rounded-2xl bg-white p-5 shadow-[var(--shadow-float)]"
        >
          <h3 className="display text-2xl text-slate-900">Guests</h3>

          <div className="mt-4 space-y-2.5">
            {rows.map((row) => {
              const current = value[row.key];
              return (
                <div
                  key={row.key}
                  className="flex items-center justify-between gap-3 rounded-xl bg-slate-50 px-4 py-3"
                >
                  <div>
                    <div className="text-[15px] font-bold text-slate-900">{row.label}</div>
                    <div className="text-xs text-slate-500">{row.hint}</div>
                  </div>
                  <div className="flex items-center gap-2">
                    <Stepper
                      kind="minus"
                      label={`Fewer ${row.label.toLowerCase()}`}
                      disabled={current <= row.min}
                      onClick={() => set({ [row.key]: current - 1 } as Partial<Travellers>)}
                    />
                    <span className="tabular grid h-9 w-11 place-items-center rounded-lg border border-slate-700 bg-white text-sm font-bold text-slate-900">
                      {current}
                    </span>
                    <Stepper
                      kind="plus"
                      label={`More ${row.label.toLowerCase()}`}
                      disabled={current >= row.max || total >= MAX_PARTY}
                      onClick={() => set({ [row.key]: current + 1 } as Partial<Travellers>)}
                    />
                  </div>
                </div>
              );
            })}
          </div>

          <p className="mt-4 border-t border-slate-200 pt-3 text-[11px] leading-relaxed text-slate-500">
            Each infant travels with an adult. A child or infant can't travel without an adult on the
            booking.
          </p>

          <div className="mt-3 flex justify-end">
            <button
              type="button"
              onClick={() => setOpen(false)}
              className="rounded-full border border-slate-300 bg-white px-7 py-2.5 text-sm font-semibold text-slate-900 transition hover:bg-slate-50"
            >
              Continue
            </button>
          </div>
        </div>
      ) : null}
    </div>
  );
}

/** Square steppers: grey minus, deep-teal plus - the carrier's exact pair. */
function Stepper({
  kind,
  label,
  disabled,
  onClick,
}: {
  kind: 'minus' | 'plus';
  label: string;
  disabled: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      disabled={disabled}
      onClick={onClick}
      className={
        'grid h-9 w-9 place-items-center rounded-lg text-lg font-semibold transition disabled:cursor-not-allowed ' +
        (kind === 'plus'
          ? 'bg-brand-900 text-white hover:bg-brand-800 disabled:bg-slate-200 disabled:text-slate-400'
          : 'bg-slate-200 text-slate-600 hover:bg-slate-300 disabled:bg-slate-100 disabled:text-slate-300')
      }
    >
      {kind === 'plus' ? '+' : '−'}
    </button>
  );
}
