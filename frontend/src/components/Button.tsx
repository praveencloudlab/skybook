import type { ButtonHTMLAttributes } from 'react';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'ghost';
  /**
   * Shows a working state and disables the button.
   *
   * <p>Important on this platform specifically: several actions kick off
   * asynchronous work, and a button that stays live invites a second booking or
   * a second payment attempt while the first is still in flight.
   */
  busy?: boolean;
}

const VARIANTS = {
  primary:
    'bg-brand-600 text-white hover:bg-brand-700 focus-visible:ring-brand-500/50 disabled:bg-brand-600/50',
  secondary:
    'border border-slate-300 bg-white text-slate-800 hover:bg-slate-50 focus-visible:ring-slate-400/40 disabled:text-slate-400',
  ghost: 'text-brand-700 hover:bg-brand-50 focus-visible:ring-brand-500/30 disabled:text-slate-400',
} as const;

export function Button({
  variant = 'primary',
  busy = false,
  disabled,
  children,
  className = '',
  ...rest
}: ButtonProps) {
  return (
    <button
      // Announce the working state instead of only showing it, so it is not
      // invisible to anyone using a screen reader.
      aria-busy={busy || undefined}
      disabled={disabled || busy}
      className={
        'inline-flex items-center justify-center gap-2 rounded px-4 py-2 text-sm font-medium ' +
        'transition outline-none focus-visible:ring-2 disabled:cursor-not-allowed ' +
        VARIANTS[variant] +
        (className ? ` ${className}` : '')
      }
      {...rest}
    >
      {busy ? 'Working…' : children}
    </button>
  );
}
