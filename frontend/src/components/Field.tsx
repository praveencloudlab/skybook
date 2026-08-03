import { useState, type InputHTMLAttributes, type ReactNode } from 'react';

/**
 * A labelled input with room for an error and a hint.
 *
 * <p>Errors are wired with `aria-invalid` + `aria-describedby` rather than being
 * shown as loose red text: a screen-reader user filling a passport field needs
 * to hear <em>which</em> field is wrong and why, not just that something is.
 *
 * <p>Password fields get a show/hide eye automatically - typing a strong
 * password blind is how typos become lockouts. The toggle never submits
 * (type="button") and announces its state for screen readers.
 */
interface FieldProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string;
  error?: string;
  hint?: ReactNode;
}

export function Field({ label, error, hint, id, className = '', type, ...input }: FieldProps) {
  const fieldId = id ?? `field-${label.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`;
  const errorId = `${fieldId}-error`;
  const hintId = `${fieldId}-hint`;
  const isPassword = type === 'password';
  const [revealed, setRevealed] = useState(false);

  return (
    <div className="space-y-1.5">
      <label htmlFor={fieldId} className="block text-sm font-medium text-slate-700">
        {label}
      </label>
      <div className={isPassword ? 'relative' : undefined}>
      <input
        id={fieldId}
        type={isPassword && revealed ? 'text' : type}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : hint ? hintId : undefined}
        className={
          'w-full rounded-xl border bg-slate-50/60 px-3.5 py-2.5 text-sm outline-none transition ' +
          'focus:bg-white focus:ring-4 focus:ring-brand-500/15 ' +
          (isPassword ? 'pr-11 ' : '') +
          (error
            ? 'border-red-300 bg-red-50/50 focus:border-red-400 focus:ring-red-500/15'
            : 'border-slate-200 focus:border-brand-500') +
          (className ? ` ${className}` : '')
        }
        {...input}
      />
      {isPassword ? (
        <button
          type="button"
          onClick={() => setRevealed((r) => !r)}
          aria-label={revealed ? 'Hide password' : 'Show password'}
          aria-pressed={revealed}
          tabIndex={-1}
          className="absolute right-2 top-1/2 grid h-8 w-8 -translate-y-1/2 place-items-center rounded-lg text-slate-400 transition hover:bg-slate-100 hover:text-slate-600"
        >
          {revealed ? (
            // Eye with a slash: currently visible, click to hide.
            <svg viewBox="0 0 24 24" className="h-4.5 w-4.5 fill-current" aria-hidden="true">
              <path d="M12 6a9.8 9.8 0 0 1 9 6 9.9 9.9 0 0 1-3.2 3.9l1.6 1.6-1.4 1.4L4.6 5.5 6 4.1l2.5 2.5A9.7 9.7 0 0 1 12 6zm-1.3 3.5 4.8 4.8A3.5 3.5 0 0 0 10.7 9.5zM3 12a10.5 10.5 0 0 1 2.4-3.3l1.5 1.5A8.6 8.6 0 0 0 5 12a8.7 8.7 0 0 0 9.6 4.3l1.6 1.6A9.9 9.9 0 0 1 12 18a9.8 9.8 0 0 1-9-6z" />
            </svg>
          ) : (
            <svg viewBox="0 0 24 24" className="h-4.5 w-4.5 fill-current" aria-hidden="true">
              <path d="M12 6a9.8 9.8 0 0 1 9 6 9.8 9.8 0 0 1-18 0 9.8 9.8 0 0 1 9-6zm0 2a7.8 7.8 0 0 0-6.9 4A7.8 7.8 0 0 0 12 16a7.8 7.8 0 0 0 6.9-4A7.8 7.8 0 0 0 12 8zm0 1.5A2.5 2.5 0 1 1 9.5 12 2.5 2.5 0 0 1 12 9.5z" />
            </svg>
          )}
        </button>
      ) : null}
      </div>
      {error ? (
        <p id={errorId} role="alert" className="text-sm text-red-600">
          {error}
        </p>
      ) : hint ? (
        <div id={hintId} className="text-xs text-slate-500">
          {hint}
        </div>
      ) : null}
    </div>
  );
}
