import type { InputHTMLAttributes, ReactNode } from 'react';

/**
 * A labelled input with room for an error and a hint.
 *
 * <p>Errors are wired with `aria-invalid` + `aria-describedby` rather than being
 * shown as loose red text: a screen-reader user filling a passport field needs
 * to hear <em>which</em> field is wrong and why, not just that something is.
 */
interface FieldProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string;
  error?: string;
  hint?: ReactNode;
}

export function Field({ label, error, hint, id, className = '', ...input }: FieldProps) {
  const fieldId = id ?? `field-${label.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`;
  const errorId = `${fieldId}-error`;
  const hintId = `${fieldId}-hint`;

  return (
    <div className="space-y-1.5">
      <label htmlFor={fieldId} className="block text-sm font-medium text-slate-700">
        {label}
      </label>
      <input
        id={fieldId}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : hint ? hintId : undefined}
        className={
          'w-full rounded border px-3 py-2 text-sm outline-none transition ' +
          'focus:ring-2 focus:ring-brand-500/40 ' +
          (error
            ? 'border-red-400 bg-red-50/40 focus:border-red-500'
            : 'border-slate-300 focus:border-brand-500') +
          (className ? ` ${className}` : '')
        }
        {...input}
      />
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
