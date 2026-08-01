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
const CURRENCY = 'GBP';

/**
 * My trips (FRONTEND_MODULE.md §5 screen 8).
 *
 * <p>Backed by {@code GET /api/bookings/mine}, which is owner-scoped by
 * construction - the subject comes from the token, so there is no id to tamper
 * with. Each row summarises the trip (reference, when it was booked, who is on
 * it, seats, cabin, total) so the list is scannable without opening anything.
 */
type TripGroup = 'pendingCheckIn' | 'checkedIn' | 'completed' | 'noShow' | 'cancelled';

/** Display order: things needing action first, history last. */
const GROUPS: Array<{ id: TripGroup; label: string; hint: string }> = [
  { id: 'pendingCheckIn', label: 'Pending check-in', hint: 'Upcoming trips nobody has checked in for yet.' },
  { id: 'checkedIn', label: 'Checked in', hint: 'Upcoming trips with a boarding pass issued.' },
  { id: 'completed', label: 'Completed trips', hint: 'Journeys already flown.' },
  { id: 'noShow', label: 'No show', hint: 'The flight left, but nobody had checked in.' },
  { id: 'cancelled', label: 'Cancelled', hint: 'Cancelled bookings and their refunds.' },
];

/**
 * A trip's lifecycle group, derived from what actually happened rather than
 * the booking status alone: passenger check-in states (the mirror from
 * checkin-service) plus the LAST leg's departure - a round trip stays
 * "upcoming" until its return has flown. Until the flights load, the
 * booking status is the best available signal.
 */
export function groupOf(booking: Booking, legs: Flight[]): TripGroup {
  if (booking.bookingStatus === 'CANCELLED') return 'cancelled';

  const active = booking.passengers.filter((p) => !p.cancelled);
  const anyCheckedIn = active.some(
    (p) => p.checkInStatus === 'CHECKED_IN' || p.checkInStatus === 'BOARDED',
  );
  const lastDeparture = legs.length
    ? Math.max(...legs.map((f) => new Date(f.departureTime).getTime()))
    : null;
  const allDeparted = lastDeparture !== null && lastDeparture < Date.now();

  if (booking.bookingStatus === 'COMPLETED' || allDeparted) {
    // The journey is over. If nobody ever checked in, that trip was a
    // no-show, not a completed one - the ticket says so too (OPEN coupons
    // stay OPEN; a no-show is not FLOWN).
    const anyTravelled = active.some(
      (p) => p.checkInStatus === 'CHECKED_IN' || p.checkInStatus === 'BOARDED',
    );
    const anyNoShow = active.some((p) => p.checkInStatus === 'NO_SHOW');
    return !anyTravelled && (anyNoShow || active.length > 0) ? 'noShow' : 'completed';
  }

  return anyCheckedIn ? 'checkedIn' : 'pendingCheckIn';
}

export function MyBookingsPage({ onOpen }: { onOpen: (booking: Booking) => void }) {
  const [bookings, setBookings] = useState<Booking[] | null>(null);
  // Flights keyed by id - the booking only carries flight ids, but "when am I
  // flying" is the thing people actually scan this list for, so we fetch them.
  // EVERY segment's flight is needed: the lifecycle grouping keys on the LAST
  // leg's departure, or a round trip would look completed after its outbound.
  const [flights, setFlights] = useState<Record<number, Flight>>({});
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    bookingsApi
      .mine(controller.signal)
      .then((list) => {
        setBookings(list);
        const ids = [
          ...new Set(
            list.flatMap((b) => [
              b.flightId,
              ...(b.segments ?? []).map((segment) => segment.flightId),
            ]),
          ),
        ];
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

  /** The booking's legs among the flights loaded so far. */
  const legsOf = (booking: Booking): Flight[] =>
    (booking.segments?.length
      ? booking.segments.map((segment) => flights[segment.flightId])
      : [flights[booking.flightId]]
    ).filter(Boolean) as Flight[];

  return (
    <main className="mx-auto max-w-3xl px-6 py-8">
      <h1 className="display text-3xl text-slate-900">My trips</h1>
      <p className="mt-2 text-sm text-slate-500">Your bookings, newest first. Tap one to see the itinerary and check in.</p>

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

        {/* Lifecycle groups: pending check-in, checked in, completed, no show,
            cancelled - each with its own header; empty groups don't render. */}
        {bookings && bookings.length > 0
          ? GROUPS.map((group) => {
              const members = bookings.filter((b) => groupOf(b, legsOf(b)) === group.id);
              if (members.length === 0) return null;
              return (
                <section key={group.id} className="pt-3 first:pt-0">
                  <div className="mb-2 flex items-baseline gap-2">
                    <h2 className="text-sm font-bold uppercase tracking-wide text-slate-700">
                      {group.label}
                    </h2>
                    <span className="tabular text-xs font-semibold text-slate-400">{members.length}</span>
                    <span className="hidden text-xs text-slate-400 sm:inline">· {group.hint}</span>
                  </div>
                  <div className="space-y-3">{members.map(renderTrip)}</div>
                </section>
              );
            })
          : null}
      </div>
    </main>
  );

  function renderTrip(booking: Booking) {
          const seats = booking.passengers.map((p) => p.seatNumber).filter(Boolean) as string[];
          const cabin = booking.passengers[0]?.travelClass;
          const flight = flights[booking.flightId];
          return (
            <button
              key={booking.id}
              type="button"
              onClick={() => onOpen(booking)}
              className="group flex w-full items-center gap-4 overflow-hidden rounded-2xl bg-white px-5 py-4 text-left ring-1 ring-slate-200 transition duration-200 ease-out hover:-translate-y-0.5 hover:shadow-[var(--shadow-lift)] hover:ring-brand-200"
            >
              {/* Route monogram - the trip's visual anchor. */}
              <span className="grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-brand-600">
                <svg viewBox="0 0 24 24" className="h-5 w-5 fill-white" aria-hidden="true">
                  <path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z" />
                </svg>
              </span>

              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                  {/* When you're flying, up front - the thing people scan for. */}
                  {flight ? (
                    <span className="tabular text-[15px] font-bold tracking-tight text-slate-900">
                      {flight.originAirportCode}
                      <span className="mx-1 text-brand-600">→</span>
                      {flight.destinationAirportCode}
                      <span className="ml-2 text-sm font-medium text-slate-500">
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
                <p className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-slate-500">
                  {flight ? (
                    <span className="tabular rounded-md bg-slate-100 px-1.5 py-0.5 font-mono font-semibold text-slate-600">
                      {booking.bookingReference}
                    </span>
                  ) : null}
                  <span>
                    {booking.passengers.length} passenger{booking.passengers.length === 1 ? '' : 's'}
                    {cabin ? ` · ${TRAVEL_CLASS_LABELS[cabin]}` : ''}
                    {seats.length ? ` · seat ${seats.join(', ')}` : ''}
                    {' · booked '}
                    {dayAndMonth(booking.bookingDate)}
                  </span>
                </p>
              </div>

              <div className="flex shrink-0 items-center gap-3">
                <p className="tabular text-[15px] font-bold text-slate-900">{money(booking.totalFare, CURRENCY)}</p>
                <span className="grid h-8 w-8 place-items-center rounded-full bg-slate-50 text-slate-400 ring-1 ring-inset ring-slate-200 transition group-hover:bg-brand-50 group-hover:text-brand-600 group-hover:ring-brand-200">
                  <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current transition-transform duration-200 group-hover:translate-x-0.5" aria-hidden="true">
                    <path d="M9 6l6 6-6 6-1.4-1.4L12.2 12 7.6 7.4z" />
                  </svg>
                </span>
              </div>
            </button>
    );
  }
}
