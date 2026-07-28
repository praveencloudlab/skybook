import type { ButtonHTMLAttributes } from 'react';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'ghost';
  size?: 'md' | 'lg';
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
  // Solid brand with a soft brand-tinted glow that lifts on hover - the clean
  // modern CTA, not a gradient (gradients on buttons age quickly).
  primary:
    'bg-brand-600 text-white shadow-[var(--shadow-btn)] hover:bg-brand-500 hover:-translate-y-0.5 active:translate-y-0 focus-visible:ring-brand-500/50 disabled:bg-brand-300 disabled:shadow-none disabled:hover:translate-y-0',
  secondary:
    'border border-slate-200 bg-white text-slate-700 shadow-sm hover:bg-slate-50 hover:border-slate-300 hover:-translate-y-0.5 active:translate-y-0 focus-visible:ring-slate-400/40 disabled:text-slate-400 disabled:hover:translate-y-0',
  ghost:
    'text-brand-700 hover:bg-brand-50 focus-visible:ring-brand-500/30 disabled:text-slate-400',
} as const;

// Pills, not rounded rectangles - the single strongest "current-era" cue a
// control can carry, and generous horizontal padding to let them breathe.
const SIZES = {
  md: 'px-5 py-2.5 text-sm',
  lg: 'px-7 py-3 text-base',
} as const;

export function Button({
  variant = 'primary',
  size = 'md',
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
        'inline-flex items-center justify-center gap-2 rounded-full font-semibold ' +
        'transition-all duration-150 outline-none focus-visible:ring-2 disabled:cursor-not-allowed ' +
        SIZES[size] +
        ' ' +
        VARIANTS[variant] +
        (className ? ` ${className}` : '')
      }
      {...rest}
    >
      {busy ? (
        <>
          <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-t-transparent" />
          Working…
        </>
      ) : (
        children
      )}
    </button>
  );
}
