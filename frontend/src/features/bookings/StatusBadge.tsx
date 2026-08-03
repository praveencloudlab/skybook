import type { BookingStatus } from '../../api/bookings';

/**
 * Booking status, coloured by what it MEANS for the passenger.
 *
 * <p>CANCELLED is slate, not red: it is a normal outcome someone chose, and red
 * would make a routine cancellation look like a fault. Shared by the list and
 * the detail so the same status never reads two different ways.
 */
const STYLES: Record<BookingStatus, string> = {
  CONFIRMED: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
  CREATED: 'bg-amber-50 text-amber-800 ring-amber-200',
  DRAFT: 'bg-slate-50 text-slate-600 ring-slate-200',
  COMPLETED: 'bg-brand-50 text-brand-700 ring-brand-200',
  PARTIALLY_CANCELLED: 'bg-orange-50 text-orange-700 ring-orange-200',
  CANCELLED: 'bg-slate-100 text-slate-500 ring-slate-200',
};

export function StatusBadge({ status, className = '' }: { status: BookingStatus; className?: string }) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium capitalize ring-1 ring-inset ${STYLES[status]} ${className}`}
    >
      <span className="h-1.5 w-1.5 rounded-full bg-current opacity-70" aria-hidden="true" />
      {status.toLowerCase().replace(/_/g, ' ')}
    </span>
  );
}
