import { useEffect, useState } from 'react';
import { bookingsApi, type Booking, type BookingStatus } from '../../api/bookings';
import { ErrorAlert } from '../../components/Alert';
import { ApiError } from '../../lib/errors';
import { money } from '../../lib/format';

/**
 * My bookings (FRONTEND_MODULE.md §5 screen 8).
 *
 * <p>Backed by {@code GET /api/bookings/mine}, which was added for this screen:
 * the existing list-all and search endpoints are ADMIN-only (a passenger gets
 * 403), so before it a passenger could only retrieve a booking they already knew
 * the PNR of. The new endpoint is owner-scoped by construction - the subject
 * comes from the token, so there is no id to tamper with.
 */
export function MyBookingsPage({ onOpen }: { onOpen: (booking: Booking) => void }) {
  const [bookings, setBookings] = useState<Booking[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    bookingsApi
      .mine(controller.signal)
      .then(setBookings)
      .catch((cause) => {
        if (cause instanceof DOMException && cause.name === 'AbortError') return;
        setError(cause instanceof ApiError ? cause : null);
      });
    return () => controller.abort();
  }, []);

  return (
    <main className="mx-auto max-w-3xl px-6 py-10">
      <h1 className="text-2xl font-semibold tracking-tight text-slate-900">My bookings</h1>

      <div className="mt-6 space-y-3">
        <ErrorAlert error={error} />

        {bookings === null && !error ? (
          <p className="text-sm text-slate-500">Loading…</p>
        ) : null}

        {bookings?.length === 0 ? (
          <p className="card px-4 py-6 text-center text-sm text-slate-600">
            You have no bookings yet.
          </p>
        ) : null}

        {bookings?.map((booking) => (
          <button
            key={booking.id}
            type="button"
            onClick={() => onOpen(booking)}
            className="card card-hover block w-full px-4 py-3 text-left transition hover:border-brand-300 hover:bg-brand-50/30"
          >
            <div className="flex items-center justify-between gap-4">
              <div>
                <p className="font-mono text-sm font-semibold tracking-wider text-brand-700">
                  {booking.bookingReference}
                </p>
                <p className="mt-0.5 text-sm text-slate-600">
                  {booking.passengers.length} passenger
                  {booking.passengers.length === 1 ? '' : 's'}
                  {booking.passengers[0]?.seatNumber
                    ? ` · seat ${booking.passengers.map((p) => p.seatNumber).filter(Boolean).join(', ')}`
                    : null}
                </p>
              </div>
              <div className="text-right">
                <StatusBadge status={booking.bookingStatus} />
                <p className="tabular mt-1 text-sm text-slate-900">{money(booking.totalFare, 'USD')}</p>
              </div>
            </div>
          </button>
        ))}
      </div>
    </main>
  );
}

/**
 * Status, coloured by what it means for the passenger.
 *
 * <p>CANCELLED is not an error - it is a normal outcome someone chose - so it is
 * slate rather than red. Red here would make a routine cancellation look like
 * something had gone wrong.
 */
function StatusBadge({ status }: { status: BookingStatus }) {
  const styles: Record<BookingStatus, string> = {
    CONFIRMED: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
    CREATED: 'bg-amber-50 text-amber-800 ring-amber-200',
    DRAFT: 'bg-slate-50 text-slate-600 ring-slate-200',
    COMPLETED: 'bg-brand-50 text-brand-700 ring-brand-200',
    CANCELLED: 'bg-slate-100 text-slate-500 ring-slate-200',
  };

  return (
    <span
      className={`inline-block rounded px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${styles[status]}`}
    >
      {status.toLowerCase()}
    </span>
  );
}
