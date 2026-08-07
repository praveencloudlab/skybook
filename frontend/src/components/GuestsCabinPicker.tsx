import { t } from '../lib/i18n';
import { useEffect, useRef, useState } from 'react';
import { TRAVEL_CLASS_LABELS, TRAVEL_CLASS_ORDER, type TravelClass } from '../api/quotes';
import { totalTravellers, type Travellers } from './TravellersPicker';

/**
 * The "Guests and Cabin" widget field - the premium-carrier booking panel's
 * combined control: an outlined tile reading "1 Guest, Economy" that opens a
 * full-width panel with the guest rows (square steppers - grey minus,
 * deep-teal plus, boxed count) on the left and the cabin radio list on the
 * right, closed by a bordered Continue pill.
 *
 * <p>The panel spans the whole booking widget: the root is intentionally
 * NOT positioned, so the absolute panel resolves against the widget's
 * relative form container.
 *
 * <p>Guest limits mirror airline policy (and the server's guardian rule): at
 * least one adult, and no more infants than adults. The cabin chosen here
 * carries into the fare step as the preselected cabin - it never hides the
 * others, so changing your mind costs nothing.
 */
export function GuestsCabinPicker({
  travellers,
  cabin,
  onTravellers,
  onCabin,
}: {
  travellers: Travellers;
  cabin: TravelClass;
  onTravellers: (value: Travellers) => void;
  onCabin: (value: TravelClass) => void;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  // Close on outside click / Escape - the panel must never trap the page.
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
    const next = { ...travellers, ...patch };
    // An infant needs an adult's lap; dropping adults drops surplus infants.
    if (next.infants > next.adults) {
      next.infants = next.adults;
    }
    onTravellers(next);
  }

  const MAX_PARTY = 9;
  const total = totalTravellers(travellers);

  const rows: Array<{ key: keyof Travellers; label: string; hint: string; min: number; max: number }> = [
    { key: 'adults', label: 'Adults', hint: 'Age 12+', min: 1, max: MAX_PARTY - travellers.children - travellers.infants },
    { key: 'children', label: 'Children', hint: 'Age 2 — 11 years', min: 0, max: MAX_PARTY - travellers.adults - travellers.infants },
    { key: 'infants', label: 'Infants', hint: 'Under 2 years', min: 0, max: Math.min(travellers.adults, MAX_PARTY - travellers.adults - travellers.children) },
  ];

  return (
    <div ref={rootRef} className="static text-sm">
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
          <span className="block text-xs font-semibold text-slate-500">{t('widget.guestsCabin')}</span>
          <span className="tabular block truncate text-[15px] font-bold text-slate-900">
            {total} Guest{total === 1 ? '' : 's'}, {TRAVEL_CLASS_LABELS[cabin]}
          </span>
        </span>
      </button>

      {open ? (
        <>
          {/*
            Phone: a bottom sheet, not a dropdown.

            The panel is absolutely positioned, so it never displaced the
            fields - but it is tall, it opens below a widget already near the
            top of the page, and the document grows to contain it. The result
            on a phone is the page lurching downward the moment you tap a
            field. A sheet is fixed to the viewport: it cannot lengthen the
            document, it lands under the thumb rather than above it, and the
            backdrop gives an obvious way out. Unchanged from sm upward.
          */}
          <div
            className="fixed inset-0 z-40 bg-slate-900/40 sm:hidden"
            aria-hidden="true"
            onClick={() => setOpen(false)}
          />
        <div
          role="dialog"
          aria-label="Choose guests and cabin"
          className="fixed inset-x-0 bottom-0 z-50 max-h-[85vh] overflow-y-auto rounded-t-2xl bg-white p-5 pb-[max(1.25rem,env(safe-area-inset-bottom))] shadow-[var(--shadow-float)] sm:absolute sm:inset-x-0 sm:bottom-auto sm:top-full sm:z-30 sm:mt-3 sm:max-h-none sm:rounded-2xl sm:p-8 sm:pb-8"
        >
          <div className="grid gap-8 md:grid-cols-2">
            {/* Guests */}
            <div>
              <h3 className="display text-2xl text-slate-900">Guests</h3>
              <div className="mt-4 space-y-2.5">
                {rows.map((row) => {
                  const current = travellers[row.key];
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
              <p className="mt-3 text-[11px] leading-relaxed text-slate-500">
                Each infant travels with an adult. A child or infant can't travel without an adult on
                the booking.
              </p>
            </div>

            {/* Cabin + rewards */}
            <div>
              <h3 className="display text-2xl text-slate-900">Cabin</h3>
              <div className="mt-4 rounded-xl bg-slate-50 px-4 py-1">
                {TRAVEL_CLASS_ORDER.map((travelClass) => (
                  <label
                    key={travelClass}
                    className="flex cursor-pointer items-center justify-between border-b border-slate-200 py-3 text-[15px] font-medium text-slate-900 last:border-b-0"
                  >
                    {TRAVEL_CLASS_LABELS[travelClass]}
                    <input
                      type="radio"
                      name="cabin"
                      value={travelClass}
                      checked={cabin === travelClass}
                      onChange={() => onCabin(travelClass)}
                      className="h-5 w-5 border-slate-400 text-slate-900 focus:ring-brand-500/30"
                    />
                  </label>
                ))}
              </div>

              <h3 className="display mt-6 text-2xl text-slate-900">Rewards &amp; Discounts</h3>
              <input
                type="text"
                disabled
                placeholder="Promo code"
                title="Coming soon"
                className="mt-3 w-full cursor-not-allowed rounded-xl bg-slate-50 px-4 py-3.5 text-sm text-slate-500 outline-none placeholder:text-slate-500"
              />
            </div>
          </div>

          <div className="mt-6 flex justify-end border-t border-slate-200 pt-4">
            <button
              type="button"
              onClick={() => setOpen(false)}
              className="rounded-full border border-slate-300 bg-white px-8 py-2.5 text-sm font-semibold text-slate-900 transition hover:bg-slate-50"
            >
              Continue
            </button>
          </div>
        </div>
        </>
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
