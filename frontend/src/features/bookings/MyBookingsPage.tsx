import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { bookingsApi, type Booking } from '../../api/bookings';
import { flightsApi, type Flight } from '../../api/flights';
import { TRAVEL_CLASS_LABELS } from '../../api/quotes';
import { ErrorAlert } from '../../components/Alert';
import { ApiError } from '../../lib/errors';
import { dayAndMonth, money, time } from '../../lib/format';
import { StatusBadge } from './StatusBadge';

// Seeded fares are USD; the booking doesn't carry a currency of its own.
const CURRENCY = 'USD';

/**
 * My trips (FRONTEND_MODULE.md §5 screen 8).
 *
 * <p>Backed by {@code GET /api/bookings/mine}, which is owner-scoped by
 * construction - the subject comes from the token, so there is no id to tamper
 * with. Each row summarises the trip (reference, when it was booked, who is on
 * it, seats, cabin, total) so the list is scannable without opening anything.
 */
type TripFilter = 'all' | 'upcoming' | 'completed' | 'cancelled';

const FILTERS: Array<{ id: TripFilter; label: string }> = [
  { id: 'all', label: 'All' },
  { id: 'upcoming', label: 'Upcoming' },
  { id: 'completed', label: 'Completed' },
  { id: 'cancelled', label: 'Cancelled' },
];

function matchesFilter(booking: Booking, filter: TripFilter): boolean {
  switch (filter) {
    case 'upcoming':
      return booking.bookingStatus === 'CONFIRMED' || booking.bookingStatus === 'CREATED';
    case 'completed':
      return booking.bookingStatus === 'COMPLETED';
    case 'cancelled':
      return booking.bookingStatus === 'CANCELLED';
    default:
      return true;
  }
}

export function MyBookingsPage({ onOpen }: { onOpen: (booking: Booking) => void }) {
  const [bookings, setBookings] = useState<Booking[] | null>(null);
  // Flights keyed by id - the booking only carries a flightId, but "when am I
  // flying" is the thing people actually scan this list for, so we fetch them.
  const [flights, setFlights] = useState<Record<number, Flight>>({});
  const [filter, setFilter] = useState<TripFilter>('all');
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    bookingsApi
      .mine(controller.signal)
      .then((list) => {
        setBookings(list);
        // Fetch each distinct flight (public data) so the row can show the date
        // and departure/arrival times. A miss just leaves that row without them.
        const ids = [...new Set(list.map((b) => b.flightId))];
        ids.forEach((id) => {
          flightsApi
            .byId(id, controller.signal)
            .then((flight) => setFlights((current) => ({ ...current, [id]: flight })))
            .catch(() => {});
        });
      })
      .catch((cause) => {
        if (cause instanceof DOMException && cause.name === 'AbortError') return;
        setError(cause instanceof ApiError ? cause : null);
      });
    return () => controller.abort();
  }, []);

  return (
    <main className="mx-auto max-w-3xl px-6 py-8">
      <h1 className="text-2xl font-semibold tracking-tight text-slate-900">My trips</h1>
      <p className="mt-1 text-sm text-slate-500">Your bookings, newest first. Tap one to see the itinerary and check in.</p>

      {/* Status tabs (Module 11). Only shown once there's something to filter. */}
      {bookings && bookings.length > 0 ? (
        <div className="mt-5 inline-flex gap-1 rounded-xl bg-slate-100 p-1">
          {FILTERS.map((f) => {
            const count = bookings.filter((b) => matchesFilter(b, f.id)).length;
            return (
              <button
                key={f.id}
                type="button"
                onClick={() => setFilter(f.id)}
                aria-pressed={filter === f.id}
                className={
                  'rounded-lg px-3 py-1.5 text-sm font-medium transition ' +
                  (filter === f.id ? 'bg-white text-brand-700 shadow-sm' : 'text-slate-500 hover:text-slate-700')
                }
              >
                {f.label}
                <span className="tabular ml-1.5 text-xs text-slate-400">{count}</span>
              </button>
            );
          })}
        </div>
      ) : null}

      <div className="mt-6 space-y-3">
        <ErrorAlert error={error} />

        {bookings === null && !error ? <p className="text-sm text-slate-500">Loading…</p> : null}

        {bookings?.length === 0 ? (
          <div className="card px-6 py-10 text-center">
            <p className="text-sm text-slate-600">You have no trips yet.</p>
            <Link
              to="/search"
              className="mt-3 inline-flex items-center gap-1 text-sm font-semibold text-brand-700 hover:underline"
            >
              Search flights →
            </Link>
          </div>
        ) : null}

        {bookings && bookings.length > 0 && bookings.filter((b) => matchesFilter(b, filter)).length === 0 ? (
          <p className="card px-4 py-6 text-center text-sm text-slate-500">No {filter} trips.</p>
        ) : null}

        {bookings?.filter((b) => matchesFilter(b, filter)).map((booking) => {
          const seats = booking.passengers.map((p) => p.seatNumber).filter(Boolean) as string[];
          const cabin = booking.passengers[0]?.travelClass;
          const flight = flights[booking.flightId];
          return (
            <button
              key={booking.id}
              type="button"
              onClick={() => onOpen(booking)}
              className="card card-hover flex w-full items-center gap-4 px-4 py-3.5 text-left"
            >
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                  {/* When you're flying, up front - the thing people scan for. */}
                  {flight ? (
                    <span className="tabular text-sm font-semibold text-slate-900">
                      {flight.originAirportCode} → {flight.destinationAirportCode}
                      <span className="ml-2 font-normal text-slate-500">
                        {dayAndMonth(flight.departureTime)} · {time(flight.departureTime)}–{time(flight.arrivalTime)}
                      </span>
                    </span>
                  ) : (
                    <span className="font-mono text-sm font-semibold tracking-[0.12em] text-slate-900">
                      {booking.bookingReference}
                    </span>
                  )}
                  <StatusBadge status={booking.bookingStatus} />
                </div>
                <p className="mt-1 text-xs text-slate-500">
                  {flight ? <span className="tabular font-mono">{booking.bookingReference}</span> : null}
                  {flight ? ' · ' : ''}
                  {booking.passengers.length} passenger{booking.passengers.length === 1 ? '' : 's'}
                  {cabin ? ` · ${TRAVEL_CLASS_LABELS[cabin]}` : ''}
                  {seats.length ? ` · seat ${seats.join(', ')}` : ''}
                  {' · booked '}
                  {dayAndMonth(booking.bookingDate)}
                </p>
              </div>
              <div className="text-right">
                <p className="tabular text-sm font-semibold text-slate-900">{money(booking.totalFare, CURRENCY)}</p>
                <p className="mt-0.5 text-xs text-brand-700">View →</p>
              </div>
            </button>
          );
        })}
      </div>
    </main>
  );
}
