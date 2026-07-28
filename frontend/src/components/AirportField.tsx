import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { AIRPORTS } from '../api/flights';

/**
 * A type-ahead airport picker (FRONTEND_MODULE.md §5 - "suggestions must come").
 *
 * <p>Replaces the plain {@code <select>}: a passenger thinks in city names, not
 * IATA codes, so the field matches on either ("man" finds Manchester, "dxb"
 * finds Dubai) and shows the city prominently with the code beside it. The
 * committed value is always a real airport code - free text cannot leak into a
 * search, because selection is the only way to set it.
 */
interface AirportFieldProps {
  label: string;
  /** The committed airport code. */
  value: string;
  onChange: (code: string) => void;
  /** The other end of the trip - hidden so the same airport can't be both. */
  exclude?: string;
  placeholder?: string;
  /** Kept for call-site compatibility; captions now live inside the tile. */
  tone?: 'light' | 'dark';
}

function airportFor(code: string): { code: string; city: string } | undefined {
  return AIRPORTS.find((airport) => airport.code === code);
}

export function AirportField({ label, value, onChange, exclude, placeholder }: AirportFieldProps) {
  const inputId = useId();
  const listId = useId();
  const wrapRef = useRef<HTMLDivElement>(null);

  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);

  const options = useMemo(() => {
    const q = query.trim().toLowerCase();
    return AIRPORTS.filter((airport) => airport.code !== exclude).filter((airport) => {
      if (!q) {
        return true;
      }
      return airport.code.toLowerCase().includes(q) || airport.city.toLowerCase().includes(q);
    });
  }, [query, exclude]);

  // Clamp the active row whenever the option set shrinks under the cursor.
  useEffect(() => {
    setActive((index) => Math.min(index, Math.max(options.length - 1, 0)));
  }, [options.length]);

  // Close (and discard any half-typed query) on a click outside the field.
  useEffect(() => {
    function onPointerDown(event: MouseEvent) {
      if (wrapRef.current && !wrapRef.current.contains(event.target as Node)) {
        setOpen(false);
        setQuery('');
      }
    }
    document.addEventListener('mousedown', onPointerDown);
    return () => document.removeEventListener('mousedown', onPointerDown);
  }, []);

  function choose(code: string) {
    onChange(code);
    setOpen(false);
    setQuery('');
  }

  function onKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setOpen(true);
      setActive((index) => Math.min(index + 1, options.length - 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActive((index) => Math.max(index - 1, 0));
    } else if (event.key === 'Enter') {
      if (open && options[active]) {
        event.preventDefault();
        choose(options[active].code);
      }
    } else if (event.key === 'Escape') {
      setOpen(false);
      setQuery('');
    }
  }

  const selected = airportFor(value);
  // While open we echo what they're typing; when closed we show the selection.
  const display = open ? query : selected ? `${selected.city} (${selected.code})` : '';

  return (
    <div ref={wrapRef} className="relative">
      {/* The premium-carrier field tile: an outlined surface with a gold icon
          disc, the caption inside, and the value in bold beneath it. */}
      <div
        className={
          'flex items-center gap-3 rounded-xl border bg-white px-3 py-2 transition ' +
          (open ? 'border-brand-900 ring-1 ring-brand-900' : 'border-slate-300 hover:border-slate-400')
        }
      >
        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-accent-500 text-white">
          <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true">
            <path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z" />
          </svg>
        </span>
        <div className="min-w-0 flex-1">
          <label htmlFor={inputId} className="block text-xs font-semibold text-slate-500">
            {label}
          </label>
          <input
            id={inputId}
            role="combobox"
            aria-expanded={open}
            aria-controls={listId}
            aria-autocomplete="list"
            aria-activedescendant={open && options[active] ? `${listId}-${options[active].code}` : undefined}
            autoComplete="off"
            value={display}
            placeholder={placeholder ?? 'City or airport'}
            onFocus={() => {
              setOpen(true);
              setActive(0);
            }}
            onChange={(event) => {
              setQuery(event.target.value);
              setOpen(true);
              setActive(0);
            }}
            onKeyDown={onKeyDown}
            className="w-full border-0 bg-transparent p-0 text-[15px] font-bold text-slate-900 outline-none placeholder:font-normal placeholder:text-slate-400"
          />
        </div>
      </div>

      {open ? (
        <ul
          id={listId}
          role="listbox"
          className="absolute z-30 mt-1.5 max-h-72 w-full overflow-auto rounded-xl border border-slate-200 bg-white py-1 shadow-[var(--shadow-lift)]"
        >
          {options.length === 0 ? (
            <li className="px-3.5 py-2.5 text-sm text-slate-400">No matching airport</li>
          ) : (
            options.map((airport, index) => (
              <li
                key={airport.code}
                id={`${listId}-${airport.code}`}
                role="option"
                aria-selected={index === active}
                // onMouseDown, not onClick: mousedown fires before the input's
                // blur, so the selection lands before the outside-click handler
                // can close the list out from under it.
                onMouseDown={(event) => {
                  event.preventDefault();
                  choose(airport.code);
                }}
                onMouseEnter={() => setActive(index)}
                className={
                  'flex cursor-pointer items-center justify-between px-3.5 py-2 text-sm ' +
                  (index === active ? 'bg-brand-50 text-brand-900' : 'text-slate-700')
                }
              >
                <span className="font-medium">{airport.city}</span>
                <span className="tabular ml-3 text-xs font-semibold text-slate-400">{airport.code}</span>
              </li>
            ))
          )}
        </ul>
      ) : null}
    </div>
  );
}
