import { useId, useRef } from 'react';

/**
 * The date twin of AirportField's premium tile: gold calendar disc, caption,
 * bold formatted value - over an invisible native date input, so the browser
 * keeps its accessible picker while the tile matches the search widget's
 * design (the bare native input looked like a different product next to it).
 */
export function DateField({
  label = 'Date',
  value,
  onChange,
  min,
}: {
  label?: string;
  value: string;
  onChange: (iso: string) => void;
  min?: string;
}) {
  const inputId = useId();
  const inputRef = useRef<HTMLInputElement>(null);

  const display = value
    ? new Date(`${value}T00:00:00`).toLocaleDateString('en-GB', {
        weekday: 'short', day: 'numeric', month: 'short',
      })
    : 'Choose a date';

  return (
    <div
      className="relative flex cursor-pointer items-center gap-3 rounded-xl border border-slate-300 bg-white px-3 py-2 transition hover:border-slate-400 focus-within:border-brand-900 focus-within:ring-1 focus-within:ring-brand-900"
      onClick={() => {
        // Chrome supports opening the native picker programmatically; where
        // unsupported, focusing the covering input is enough.
        const el = inputRef.current;
        if (!el) return;
        (el as HTMLInputElement & { showPicker?: () => void }).showPicker?.();
        el.focus();
      }}
    >
      <span className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-accent-500 text-white">
        <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true">
          <path d="M19 4h-1V2h-2v2H8V2H6v2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2zm0 16H5V10h14v10zm0-12H5V6h14v2z" />
        </svg>
      </span>
      <div className="min-w-0 flex-1">
        <label htmlFor={inputId} className="block text-xs font-semibold text-slate-500">
          {label}
        </label>
        <span className="tabular block truncate text-sm font-bold text-slate-900">{display}</span>
      </div>
      {/* The real control: full-tile hit area, invisible, still accessible. */}
      <input
        ref={inputRef}
        id={inputId}
        type="date"
        value={value}
        min={min}
        onChange={(e) => onChange(e.target.value)}
        className="absolute inset-0 h-full w-full cursor-pointer opacity-0"
        aria-label={label}
      />
    </div>
  );
}
