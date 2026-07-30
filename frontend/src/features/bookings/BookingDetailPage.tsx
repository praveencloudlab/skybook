import { useCallback, useEffect, useState } from 'react';
import { bookingsApi, type Booking } from '../../api/bookings';
import { flightsApi, type Flight } from '../../api/flights';
import { FARE_TYPE_LABELS, TRAVEL_CLASS_LABELS } from '../../api/quotes';
import {
  checkInClosesAt,
  checkInOpensAt,
  checkinApi,
  type BoardingPass,
  type CheckIn,
} from '../../api/checkin';
import { Alert, ErrorAlert } from '../../components/Alert';
import { Button } from '../../components/Button';
import { ApiError } from '../../lib/errors';
import { dayAndMonth, duration, money, time } from '../../lib/format';
import { BoardingPassCard } from './BoardingPassCard';
import { StatusBadge } from './StatusBadge';
import { printETicket } from './printable';
import { ModifyBookingDialog } from './ModifyBookingDialog';

// Seeded fares are USD; the booking doesn't carry a currency of its own.
const CURRENCY = 'USD';

/**
 * One booking, laid out like a real itinerary (FRONTEND_MODULE.md §5 screens 8-9):
 * a header with the reference and status, the trip, the passengers and what each
 * paid, the contact on file, then check-in and the boarding pass.
 *
 * <p>Check-in records arrive asynchronously - checkin-service creates one per
 * passenger after consuming the CONFIRMED event - so a just-confirmed booking may
 * briefly have none. That is shown as "preparing", not as an error.
 */
export function BookingDetailPage({
  booking: initial,
  onBack,
}: {
  booking: Booking;
  onBack: () => void;
}) {
  const [booking, setBooking] = useState(initial);
  const [flight, setFlight] = useState<Flight | null>(null);
  // Every segment's flight, keyed by flight id - a round trip has two legs.
  const [segmentFlights, setSegmentFlights] = useState<Record<number, Flight>>({});
  // Premium per-segment date change: which segment is being changed, the
  // picked date, and the flights found for it.
  const [rebooking, setRebooking] = useState<{ segmentIndex: number; date: string } | null>(null);
  const [rebookFlights, setRebookFlights] = useState<Flight[] | null>(null);
  const [rebookBusy, setRebookBusy] = useState(false);
  const [checkIns, setCheckIns] = useState<CheckIn[] | null>(null);
  const [passes, setPasses] = useState<Record<number, BoardingPass>>({});
  const [error, setError] = useState<ApiError | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [cancelling, setCancelling] = useState(false);
  // Passenger-level cancellation (business rules 4-13): the checklist and its
  // actions stay hidden until the user explicitly opens them - a detail page
  // should read as an itinerary, not open on a cancellation form.
  const [managing, setManaging] = useState(false);
  const [modifying, setModifying] = useState(false);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [confirm, setConfirm] = useState<'selected' | 'entire' | null>(null);
  const [refundNotice, setRefundNotice] = useState<string | null>(null);

  const load = useCallback(async (signal?: AbortSignal) => {
    try {
      const [fresh, records] = await Promise.all([
        bookingsApi.byId(initial.id, signal),
        checkinApi.forBooking(initial.id, signal),
      ]);
      setBooking(fresh);
      setCheckIns(records);

      // The flights are public data; fetch every segment's leg so the trip
      // can be shown properly. A miss here just hides that leg's card.
      flightsApi.byId(fresh.flightId, signal).then(setFlight).catch(() => {});
      for (const segment of fresh.segments ?? []) {
        flightsApi
          .byId(segment.flightId, signal)
          .then((f) => setSegmentFlights((current) => ({ ...current, [f.id]: f })))
          .catch(() => {});
      }

      const issued: Record<number, BoardingPass> = {};
      await Promise.all(
        records
          .filter((record) => record.status === 'CHECKED_IN' || record.status === 'BOARDED')
          .map(async (record) => {
            try {
              issued[record.id] = await checkinApi.boardingPass(record.id, signal);
            } catch {
              // A missing pass is not worth failing the whole screen for.
            }
          }),
      );
      setPasses(issued);
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') return;
      setError(cause instanceof ApiError ? cause : null);
    }
  }, [initial.id]);

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  async function handleCheckIn(record: CheckIn) {
    setError(null);
    setBusyId(record.id);
    try {
      await checkinApi.checkIn(record.id);
      const pass = await checkinApi.boardingPass(record.id);
      setPasses((current) => ({ ...current, [record.id]: pass }));
      await load();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
    } finally {
      setBusyId(null);
    }
  }

  async function runCancel(passengerIds: number[]) {
    setCancelling(true);
    setError(null);
    try {
      const result = await bookingsApi.cancelPassengers(booking.id, passengerIds);
      setBooking(result.booking);
      setSelected(new Set());
      setConfirm(null);
      setManaging(false);
      const amount = money(result.refundAmount, CURRENCY);
      setRefundNotice(
        result.bookingCancelled
          ? `Booking cancelled. A refund of ${amount} will be processed to your original payment method.`
          : `${passengerIds.length} passenger${passengerIds.length === 1 ? '' : 's'} cancelled. A refund of ${amount} will be processed for them.`,
      );
      await load();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
    } finally {
      setCancelling(false);
    }
  }

  /** Drop just the return leg - outbound travels on, coupons refund. */
  async function cancelReturnSegment(segmentIndex: number) {
    setCancelling(true);
    setError(null);
    try {
      const result = await bookingsApi.cancelSegment(booking.id, segmentIndex);
      setBooking(result.booking);
      setRefundNotice(
        `Return cancelled. A refund of ${money(result.refundAmount, CURRENCY)} will be processed to your original payment method; your outbound is unchanged.`,
      );
      await load();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
    } finally {
      setCancelling(false);
    }
  }

  async function searchRebookFlights(segmentIndex: number, date: string) {
    const segFlight = segmentFlights[(booking.segments ?? [])[segmentIndex]?.flightId ?? -1];
    if (!segFlight || !date) return;
    setRebookFlights(null);
    try {
      const found = await flightsApi.search({
        origin: segFlight.originAirportCode,
        destination: segFlight.destinationAirportCode,
        date,
      });
      setRebookFlights(found.filter((f) => f.id !== segFlight.id));
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
    }
  }

  /** Premium entitlement: same booking, same tickets, fare difference only. */
  async function confirmRebook(segmentIndex: number, newFlightId: number) {
    setRebookBusy(true);
    setError(null);
    try {
      const updated = await bookingsApi.rebookSegment(booking.id, segmentIndex, newFlightId);
      setBooking(updated);
      setRebooking(null);
      setRebookFlights(null);
      setRefundNotice('Flight date changed on the same booking — your tickets carry a fresh coupon for the new flight.');
      await load();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
    } finally {
      setRebookBusy(false);
    }
  }

  const seats = booking.passengers.map((p) => p.seatNumber).filter(Boolean) as string[];
  // The flight time is airport-local; comparing to the viewer's clock is close
  // enough to stop a departed flight from offering a cancel it can't honour.
  const departed = flight ? new Date(flight.departureTime) < new Date() : false;

  // Passenger-level cancellation state (business rules).
  const isMinor = (p: (typeof booking.passengers)[number]) =>
    p.passengerType === 'CHILD' || p.passengerType === 'INFANT';
  // Checked-in must consult checkin-service's records TOO: the booking's own
  // checkInStatus is an async Kafka read-model, and right after a check-in it
  // can still say NOT_OPEN - which briefly offered a passenger with a freshly
  // issued boarding pass for cancellation. The records on this page are fresh.
  const checkedInRecordIds = new Set(
    (checkIns ?? [])
      .filter((r) => r.status === 'CHECKED_IN' || r.status === 'BOARDED')
      .map((r) => r.bookingPassengerId),
  );
  const checkedIn = (p: (typeof booking.passengers)[number]) =>
    p.checkInStatus === 'CHECKED_IN' ||
    p.checkInStatus === 'BOARDED' ||
    checkedInRecordIds.has(p.id);

  const activePassengers = booking.passengers.filter((p) => !p.cancelled);
  // A passenger can be cancelled only while active, before check-in, before departure.
  const cancellablePassengers = activePassengers.filter((p) => !checkedIn(p) && !departed);
  const remainingAfter = activePassengers.filter((p) => !selected.has(p.id));
  // Guardian rule (mirrors the server): a child/infant can't be the only kind
  // left. Invalid if some minor remains and no adult does (and someone remains).
  const orphansMinor =
    remainingAfter.length > 0 &&
    remainingAfter.some(isMinor) &&
    !remainingAfter.some((p) => !isMinor(p));
  const selectedCount = [...selected].filter((id) => cancellablePassengers.some((p) => p.id === id)).length;
  const canCancelSelected = selectedCount > 0 && !orphansMinor && !departed;
  const anyCancellable = cancellablePassengers.length > 0;
  const anyCheckedIn = activePassengers.some(checkedIn);

  // A cancelled passenger has no seat and no valid check-in, so it must NOT
  // appear in the check-in list (its stale checkin-service record would offer a
  // Check-in that then fails at inventory). Match by BookingPassenger id, with
  // name as a fallback.
  const cancelledBpIds = new Set(booking.passengers.filter((p) => p.cancelled).map((p) => p.id));
  const cancelledNames = new Set(
    booking.passengers
      .filter((p) => p.cancelled)
      .map((p) => `${p.firstName} ${p.lastName}`.trim().toLowerCase()),
  );
  const visibleCheckIns = (checkIns ?? []).filter(
    (r) =>
      !cancelledBpIds.has(r.bookingPassengerId) &&
      !cancelledNames.has((r.passengerName ?? '').trim().toLowerCase()),
  );

  return (
    <main className="mx-auto max-w-4xl px-6 py-8">
      <button
        type="button"
        onClick={onBack}
        className="inline-flex items-center gap-1 text-sm font-medium text-slate-500 transition hover:text-brand-700"
      >
        <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true">
          <path d="M15.4 7.4 14 6l-6 6 6 6 1.4-1.4L10.8 12z" />
        </svg>
        All trips
      </button>

      {/* Header - a navy hero band, so the itinerary opens like a travel
          document rather than an admin record. */}
      <div className="relative mt-4 overflow-hidden rounded-3xl bg-brand-950 px-6 py-5 text-white shadow-[var(--shadow-glow)]">
        <div className="aurora" aria-hidden="true" />
        <div className="grid-texture absolute inset-0 opacity-60" aria-hidden="true" />
        <svg className="absolute inset-0 h-full w-full" viewBox="0 0 800 160" fill="none" aria-hidden="true">
          <path
            d="M-30 140 C 220 110, 480 60, 830 20"
            stroke="white"
            strokeOpacity="0.14"
            strokeWidth="1.5"
            strokeDasharray="5 8"
          />
        </svg>
        <div className="relative flex flex-wrap items-start justify-between gap-4">
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-[0.18em] text-white/50">
              Booking reference
            </p>
            <h1 className="mt-0.5 font-mono text-3xl font-bold tracking-[0.18em]">
              {booking.bookingReference}
            </h1>
            <p className="mt-1.5 text-sm text-white/60">Booked {dayAndMonth(booking.bookingDate)}</p>
          </div>
          <div className="flex flex-col items-end gap-2.5">
            <StatusBadge status={booking.bookingStatus} />
            <p className="tabular text-2xl font-bold">{money(booking.totalFare, CURRENCY)}</p>
            <button
              type="button"
              onClick={() =>
                printETicket(
                  booking,
                  { ...(flight ? { [flight.id]: flight } : {}), ...segmentFlights },
                  CURRENCY,
                )
              }
              className="inline-flex items-center gap-1.5 rounded-xl bg-white/10 px-3.5 py-2 text-xs font-semibold text-white ring-1 ring-inset ring-white/20 transition hover:bg-white/20"
            >
              <svg viewBox="0 0 24 24" className="h-3.5 w-3.5 fill-current" aria-hidden="true">
                <path d="M5 20h14v-2H5v2zM12 2v10.17l3.59-3.58L17 10l-5 5-5-5 1.41-1.41L12 12.17V2z" />
              </svg>
              Download e-ticket
            </button>
          </div>
        </div>
      </div>

      {/* Trip - one card per segment (a single-PNR round trip has two legs). */}
      {(booking.segments ?? []).map((seg) => {
        const segFlight = segmentFlights[seg.flightId] ?? (seg.segmentIndex === 0 ? flight : null);
        if (!segFlight) return null;
        const multi = (booking.segments?.length ?? 0) > 1;
        const segRows = booking.passengers.filter((p) => (p.segmentIndex ?? 0) === seg.segmentIndex);
        const segActive = segRows.filter((p) => !p.cancelled);
        const segDeparted = new Date(segFlight.departureTime) < new Date();
        const segCheckedIn = segActive.some(checkedIn);
        const segCancelled = seg.status === 'CANCELLED';
        const statusLabel = segCancelled ? 'Cancelled' : segDeparted ? 'Flown' : segCheckedIn ? 'Checked in' : 'Upcoming';
        const allPremium = segActive.length > 0 && segActive.every((p) => p.fareType === 'PREMIUM');
        const changeable = !segCancelled && !segDeparted && !segCheckedIn && booking.bookingStatus !== 'CANCELLED';
        return (
          <section key={seg.id} className={'card mt-6 p-5' + (segCancelled ? ' opacity-60' : '')}>
            <div className="mb-3 flex items-center gap-2">
              {multi ? (
                <span className="rounded-full bg-brand-50 px-2 py-0.5 text-[11px] font-bold uppercase tracking-wide text-brand-700 ring-1 ring-inset ring-brand-100">
                  {seg.segmentIndex === 0 ? 'Outbound' : 'Return'}
                </span>
              ) : null}
              <span className="grid h-6 w-8 place-items-center rounded bg-brand-600 text-[10px] font-bold text-white">
                {segFlight.airlineCode}
              </span>
              <span className="tabular text-sm font-medium text-slate-600">{segFlight.flightNumber}</span>
              <span
                className={
                  'rounded-full px-2 py-0.5 text-[11px] font-semibold ring-1 ring-inset ' +
                  (segCancelled
                    ? 'bg-red-50 text-red-600 ring-red-100'
                    : segDeparted
                      ? 'bg-slate-100 text-slate-500 ring-slate-200'
                      : segCheckedIn
                        ? 'bg-emerald-50 text-emerald-700 ring-emerald-100'
                        : 'bg-sky-50 text-sky-700 ring-sky-100')
                }
              >
                {statusLabel}
              </span>
              <span className="ml-auto text-xs text-slate-400">{dayAndMonth(segFlight.departureTime)}</span>
            </div>
            <div className="flex items-center gap-4">
              <div className="min-w-[4rem]">
                <div className="tabular text-2xl font-semibold tracking-tight text-slate-900">{time(segFlight.departureTime)}</div>
                <div className="text-xs font-medium tracking-wide text-slate-500">{segFlight.originAirportCode}</div>
              </div>
              <div className="flex flex-1 flex-col items-center gap-1">
                <span className="tabular text-[11px] font-medium text-slate-500">
                  {duration(segFlight.departureTime, segFlight.arrivalTime)}
                </span>
                <div className="flex w-full items-center gap-1">
                  <span className="h-1.5 w-1.5 rounded-full bg-slate-300" />
                  <span className="route-line" />
                </div>
                <span className="text-[11px] text-slate-400">Direct</span>
              </div>
              <div className="min-w-[4rem] text-right">
                <div className="tabular text-2xl font-semibold tracking-tight text-slate-900">{time(segFlight.arrivalTime)}</div>
                <div className="text-xs font-medium tracking-wide text-slate-500">{segFlight.destinationAirportCode}</div>
              </div>
            </div>

            {/* Per-segment actions (ROUND_TRIP_MODULE.md §7/§11). */}
            {changeable && multi && (seg.segmentIndex >= 1 || allPremium) ? (
              <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-slate-100 pt-3">
                {allPremium ? (
                  <button
                    type="button"
                    onClick={() => {
                      setRebooking(rebooking?.segmentIndex === seg.segmentIndex ? null : { segmentIndex: seg.segmentIndex, date: '' });
                      setRebookFlights(null);
                    }}
                    className="rounded-xl border border-slate-300 bg-white px-3.5 py-1.5 text-xs font-semibold text-slate-700 transition hover:border-brand-500 hover:text-brand-700"
                  >
                    Change date — free for Premium
                  </button>
                ) : null}
                {seg.segmentIndex >= 1 ? (
                  <button
                    type="button"
                    disabled={cancelling}
                    onClick={() => void cancelReturnSegment(seg.segmentIndex)}
                    className="rounded-xl border border-red-200 px-3.5 py-1.5 text-xs font-semibold text-red-600 transition hover:bg-red-50 disabled:opacity-50"
                  >
                    Cancel this leg &amp; refund
                  </button>
                ) : null}
              </div>
            ) : null}

            {/* Premium date change: pick a date, pick a flight - same PNR. */}
            {rebooking?.segmentIndex === seg.segmentIndex ? (
              <div className="mt-3 rounded-xl bg-slate-50 p-3 text-sm">
                <div className="flex flex-wrap items-center gap-2">
                  <input
                    type="date"
                    value={rebooking.date}
                    onChange={(e) => setRebooking({ segmentIndex: seg.segmentIndex, date: e.target.value })}
                    className="rounded-lg border border-slate-300 px-2.5 py-1.5 text-sm"
                  />
                  <button
                    type="button"
                    disabled={!rebooking.date}
                    onClick={() => void searchRebookFlights(seg.segmentIndex, rebooking.date)}
                    className="rounded-lg bg-brand-900 px-3 py-1.5 text-xs font-semibold text-white disabled:opacity-40"
                  >
                    Find flights
                  </button>
                  <span className="text-xs text-slate-500">
                    Same booking and tickets — you pay or get back only the fare difference.
                  </span>
                </div>
                {rebookFlights !== null ? (
                  rebookFlights.length === 0 ? (
                    <p className="mt-2 text-xs text-slate-500">No flights on that date — try another.</p>
                  ) : (
                    <ul className="mt-2 space-y-1.5">
                      {rebookFlights.map((f) => (
                        <li key={f.id} className="flex items-center justify-between rounded-lg bg-white px-3 py-2 ring-1 ring-slate-200">
                          <span className="tabular text-xs text-slate-700">
                            {f.flightNumber} · {time(f.departureTime)} → {time(f.arrivalTime)}
                          </span>
                          <button
                            type="button"
                            disabled={rebookBusy}
                            onClick={() => void confirmRebook(seg.segmentIndex, f.id)}
                            className="rounded-lg bg-accent-500 px-3 py-1 text-xs font-bold text-white transition hover:bg-accent-600 disabled:opacity-50"
                          >
                            {rebookBusy ? 'Moving…' : 'Select'}
                          </button>
                        </li>
                      ))}
                    </ul>
                  )
                ) : null}
              </div>
            ) : null}
          </section>
        );
      })}

      {/* Passengers + fares */}
      <section className="card mt-5 overflow-hidden">
        <div className="flex items-center justify-between border-b border-slate-100 bg-slate-50/70 px-4 py-2.5">
          <h2 className="text-sm font-semibold text-slate-700">
            Passenger{booking.passengers.length === 1 ? '' : 's'} ({booking.passengers.length})
          </h2>
          {seats.length ? <span className="tabular text-xs text-slate-500">Seats {seats.join(', ')}</span> : null}
        </div>
        <ul className="divide-y divide-slate-100">
          {booking.passengers.map((p) => {
            const surcharge = Number(p.seatSurcharge) || 0;
            const fare = Number(p.fare ?? p.baseFare) || 0;
            return (
              <li key={p.id} className="flex items-center justify-between gap-4 px-4 py-3">
                <div className="min-w-0">
                  <p className="truncate font-medium text-slate-900">
                    {p.firstName} {p.lastName}
                  </p>
                  <p className="mt-0.5 text-xs text-slate-500">
                    {(booking.segments?.length ?? 0) > 1 ? (
                      <span className="font-semibold text-brand-700">
                        {(p.segmentIndex ?? 0) === 0 ? 'Outbound' : 'Return'} ·{' '}
                      </span>
                    ) : null}
                    {TRAVEL_CLASS_LABELS[p.travelClass]} · {FARE_TYPE_LABELS[p.fareType]}
                    {p.seatNumber ? (
                      <>
                        {' '}
                        · seat <span className="tabular font-medium text-slate-700">{p.seatNumber}</span>
                      </>
                    ) : (
                      <> · seat assigned at check-in</>
                    )}
                  </p>
                </div>
                <div className="text-right">
                  <p className="tabular text-sm font-medium text-slate-900">{money(fare, CURRENCY)}</p>
                  {surcharge > 0 ? (
                    <p className="tabular text-[11px] text-slate-400">incl. seat {money(surcharge, CURRENCY)}</p>
                  ) : null}
                </div>
              </li>
            );
          })}
        </ul>
        <div className="flex items-center justify-between border-t border-slate-200 px-4 py-3">
          <span className="text-sm font-medium text-slate-900">Total paid</span>
          <span className="tabular text-sm font-semibold text-slate-900">{money(booking.totalFare, CURRENCY)}</span>
        </div>
      </section>

      {/* Contact */}
      {booking.contact ? (
        <section className="card mt-5 px-4 py-3">
          <h2 className="text-xs font-semibold uppercase tracking-wide text-slate-500">Contact</h2>
          <div className="mt-2 grid gap-1 text-sm text-slate-700 sm:grid-cols-3">
            <span>{booking.contact.contactName}</span>
            <span className="truncate text-slate-500">{booking.contact.contactEmail}</span>
            {booking.contact.contactPhone ? (
              <span className="text-slate-500">{booking.contact.contactPhone}</span>
            ) : null}
          </div>
        </section>
      ) : null}

      {/* E-tickets: one per traveller, a coupon per leg (issued at confirmation). */}
      {(booking.tickets?.length ?? 0) > 0 ? (
        <section className="card mt-5 px-4 py-3">
          <h2 className="text-xs font-semibold uppercase tracking-wide text-slate-500">E-tickets</h2>
          <ul className="mt-2 space-y-1.5 text-sm">
            {booking.tickets!.map((ticket) => {
              const owner = booking.passengers.find((p) => p.passengerId === ticket.passengerId);
              return (
                <li key={ticket.id} className="flex flex-wrap items-center justify-between gap-2">
                  <span className="text-slate-700">
                    {owner ? `${owner.firstName} ${owner.lastName}` : 'Traveller'}
                  </span>
                  <span className="flex items-center gap-2">
                    <span className="tabular font-mono text-slate-900">
                      {ticket.ticketNumber.slice(0, 3)}-{ticket.ticketNumber.slice(3)}
                    </span>
                    {ticket.coupons.map((coupon) => (
                      <span
                        key={coupon.couponNumber}
                        title={`Coupon ${coupon.couponNumber}`}
                        className={
                          'rounded-full px-2 py-0.5 text-[10px] font-semibold ring-1 ring-inset ' +
                          (coupon.status === 'OPEN'
                            ? 'bg-sky-50 text-sky-700 ring-sky-100'
                            : coupon.status === 'CHECKED_IN'
                              ? 'bg-emerald-50 text-emerald-700 ring-emerald-100'
                              : coupon.status === 'FLOWN'
                                ? 'bg-slate-100 text-slate-500 ring-slate-200'
                                : 'bg-red-50 text-red-600 ring-red-100')
                        }
                      >
                        C{coupon.couponNumber} {coupon.status}
                      </span>
                    ))}
                  </span>
                </li>
              );
            })}
          </ul>
        </section>
      ) : null}

      {/* Manage booking - cancellation (update/reschedule has no backend yet). */}
      <section className="card mt-5 p-5">
        <h2 className="text-sm font-semibold text-slate-900">Manage booking</h2>
        <div className="mt-3 rounded-xl bg-slate-50 p-4 text-sm">
          <p className="font-medium text-slate-700">Cancellation &amp; refund rules</p>
          <ul className="mt-2 space-y-1 text-slate-600">
            <li>• <span className="font-medium">Saver</span> — cancellable; a cancellation fee applies and the refund is partial.</li>
            <li>• <span className="font-medium">Flexi</span> — cancellable with a more generous refund.</li>
            <li>• <span className="font-medium">Premium</span> — fully flexible; highest refund.</li>
            <li>• A captured payment is refunded automatically to the original method; check-in closes for every passenger.</li>
          </ul>
        </div>

        {refundNotice ? (
          <div className="mt-4 rounded-xl bg-emerald-50 px-3 py-2 text-sm text-emerald-800 ring-1 ring-inset ring-emerald-200">
            {refundNotice}
          </div>
        ) : null}

        {departed ? (
          <p className="mt-4 text-sm text-slate-500">
            This flight has already departed — the booking can no longer be cancelled or changed.
          </p>
        ) : booking.bookingStatus === 'CANCELLED' ? (
          <p className="mt-4 text-sm text-slate-400">This booking is cancelled.</p>
        ) : anyCancellable && !managing ? (
          <div className="mt-4 flex flex-wrap gap-2">
            {/* Change flight/date/bags = a guided rebook; impossible once
                anyone holds a boarding pass. */}
            {!anyCheckedIn ? (
              <button
                type="button"
                onClick={() => setModifying(true)}
                className="rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm font-semibold text-slate-700 transition hover:border-brand-500 hover:text-brand-700"
              >
                Change flight, dates or bags
              </button>
            ) : null}
            <button
              type="button"
              onClick={() => {
                setManaging(true);
                setRefundNotice(null);
              }}
              className="rounded-xl border border-red-200 px-4 py-2 text-sm font-semibold text-red-600 transition hover:bg-red-50"
            >
              Cancel booking…
            </button>
          </div>
        ) : anyCancellable ? (
          <div className="mt-4">
            {/* Choose passengers to cancel (rule 12: two distinct actions). */}
            <p className="text-sm font-medium text-slate-700">Cancel passengers</p>
            <ul className="mt-2 space-y-1.5">
              {activePassengers.map((p) => {
                const locked = checkedIn(p);
                return (
                  <li key={p.id}>
                    <label
                      className={
                        'flex items-center gap-2.5 rounded-lg px-2 py-1.5 text-sm ' +
                        (locked ? 'opacity-60' : 'cursor-pointer hover:bg-slate-50')
                      }
                    >
                      <input
                        type="checkbox"
                        disabled={locked}
                        checked={selected.has(p.id)}
                        onChange={(e) => {
                          setRefundNotice(null);
                          setSelected((prev) => {
                            const next = new Set(prev);
                            if (e.target.checked) next.add(p.id);
                            else next.delete(p.id);
                            return next;
                          });
                        }}
                        className="h-4 w-4 rounded border-slate-300 text-brand-600 focus:ring-brand-500/40"
                      />
                      <span className="flex-1 font-medium text-slate-800">
                        {p.firstName} {p.lastName}
                      </span>
                      <span className="text-xs text-slate-400">
                        {p.passengerType ? p.passengerType.toLowerCase() : ''}
                        {p.seatNumber ? ` · seat ${p.seatNumber}` : ''}
                        {locked ? ' · checked in' : ''}
                      </span>
                    </label>
                  </li>
                );
              })}
            </ul>

            {orphansMinor ? (
              <p className="mt-2 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-800 ring-1 ring-inset ring-amber-200">
                A child or infant can't travel without an adult. Keep an adult on the booking, or use
                “Cancel entire booking”.
              </p>
            ) : null}

            {anyCheckedIn ? (
              <p className="mt-2 rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-600 ring-1 ring-inset ring-slate-200">
                Passengers who have already checked in can no longer be cancelled online — contact
                support to change their travel.
              </p>
            ) : null}

            <div className="mt-4 flex flex-wrap gap-2">
              <Button
                onClick={() => setConfirm('selected')}
                disabled={!canCancelSelected || cancelling}
              >
                Cancel selected passenger{selectedCount === 1 ? '' : 's'}
                {selectedCount > 0 ? ` (${selectedCount})` : ''}
              </Button>
              {/* Whole-booking cancel disappears once anyone holds a boarding
                  pass - "entire" would silently exclude them, which is worse
                  than not offering it (a checked-in traveller needs support). */}
              {!anyCheckedIn && activePassengers.length > 1 ? (
                <button
                  type="button"
                  onClick={() => setConfirm('entire')}
                  disabled={cancelling}
                  className="rounded-xl border border-red-200 px-4 py-2 text-sm font-semibold text-red-600 transition hover:bg-red-50 disabled:opacity-60"
                >
                  Cancel entire booking
                </button>
              ) : null}
              <button
                type="button"
                onClick={() => {
                  setManaging(false);
                  setSelected(new Set());
                  setConfirm(null);
                }}
                disabled={cancelling}
                className="rounded-xl border border-slate-200 px-4 py-2 text-sm font-semibold text-slate-600 transition hover:bg-slate-50 disabled:opacity-60"
              >
                Keep booking
              </button>
            </div>

            {/* Confirmation (rule 13). */}
            {confirm ? (
              <div className="mt-4 rounded-xl border border-red-200 bg-red-50 p-4">
                <p className="text-sm font-medium text-red-800">
                  {confirm === 'entire'
                    ? 'Cancel the entire booking for all passengers?'
                    : `Cancel ${selectedCount} passenger${selectedCount === 1 ? '' : 's'}?`}
                </p>
                <p className="mt-1 text-sm text-red-700">
                  This can't be undone. A refund is calculated for the cancelled passenger
                  {confirm === 'entire' || selectedCount !== 1 ? 's' : ''} per the fare rules above.
                  {confirm === 'selected' ? ' The remaining passengers keep their seats and services.' : ''}
                </p>
                <div className="mt-3 flex gap-2">
                  <Button variant="secondary" onClick={() => setConfirm(null)} disabled={cancelling}>
                    Keep
                  </Button>
                  <button
                    type="button"
                    disabled={cancelling}
                    onClick={() =>
                      runCancel(
                        confirm === 'entire'
                          ? cancellablePassengers.map((p) => p.id)
                          : [...selected].filter((id) => cancellablePassengers.some((p) => p.id === id)),
                      )
                    }
                    className="rounded-xl bg-red-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-red-700 disabled:opacity-60"
                  >
                    {cancelling
                      ? 'Cancelling…'
                      : confirm === 'entire'
                        ? 'Yes, cancel booking'
                        : 'Yes, cancel selected'}
                  </button>
                </div>
              </div>
            ) : null}
          </div>
        ) : anyCheckedIn ? (
          <p className="mt-4 rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-800 ring-1 ring-inset ring-amber-200">
            Every remaining passenger has already checked in, so this booking can no longer be
            cancelled online. To change a traveller, please contact support.
          </p>
        ) : (
          <p className="mt-4 text-sm text-slate-400">
            This booking is {booking.bookingStatus.toLowerCase().replace(/_/g, ' ')} and can no longer
            be changed.
          </p>
        )}
      </section>

      {/* Check-in + boarding passes */}
      <section className="mt-8">
        <h2 className="text-sm font-semibold text-slate-700">Check-in &amp; boarding passes</h2>
        <div className="mt-3 space-y-4">
          <ErrorAlert error={error} />

          {checkIns === null ? (
            <p className="text-sm text-slate-500">Loading…</p>
          ) : visibleCheckIns.length === 0 ? (
            <Alert tone="info">
              {booking.bookingStatus === 'CONFIRMED' || booking.bookingStatus === 'PARTIALLY_CANCELLED'
                ? 'Preparing check-in for this booking — this usually takes a few seconds.'
                : 'Check-in becomes available once your booking is confirmed.'}
            </Alert>
          ) : (
            visibleCheckIns.map((record) => (
              <CheckInRow
                key={record.id}
                record={record}
                pass={passes[record.id]}
                busy={busyId === record.id}
                onCheckIn={() => handleCheckIn(record)}
                flightArrivalTime={flight?.arrivalTime}
              />
            ))
          )}
        </div>
      </section>

      {modifying ? (
        <ModifyBookingDialog
          booking={booking}
          currentFlight={flight}
          onClose={() => setModifying(false)}
          onRebooked={() => {
            // Back to the list: the new booking is there CONFIRMED, the old
            // one CANCELLED with its refund - fresher than patching in place.
            setModifying(false);
            onBack();
          }}
        />
      ) : null}
    </main>
  );
}

function CheckInRow({
  record,
  pass,
  busy,
  onCheckIn,
  flightArrivalTime,
}: {
  record: CheckIn;
  pass?: BoardingPass;
  busy: boolean;
  onCheckIn: () => void;
  flightArrivalTime?: string;
}) {
  // Gate on the SERVER's status, never on a locally recomputed window. The times
  // below only EXPLAIN a NOT_OPEN, never decide it.
  const done = record.status === 'CHECKED_IN' || record.status === 'BOARDED';
  const canCheckIn = record.status === 'OPEN';
  const notOpenYet = record.status === 'NOT_OPEN';
  const noShow = record.status === 'NO_SHOW';
  const opens = checkInOpensAt(record.departureTime);
  const closes = checkInClosesAt(record.departureTime);

  return (
    <div className="card">
      <div className="flex items-center justify-between gap-4 px-4 py-3">
        <div>
          <p className="font-medium text-slate-900">{record.passengerName}</p>
          <p className="text-sm text-slate-600">
            {record.flightNumber} · {record.originAirportCode} → {record.destinationAirportCode}
            {record.seatNumber ? ` · seat ${record.seatNumber}` : ''}
          </p>
        </div>

        {done ? (
          <span className="rounded-full bg-emerald-50 px-2.5 py-1 text-xs font-medium text-emerald-700 ring-1 ring-inset ring-emerald-200">
            {record.status === 'BOARDED' ? 'boarded' : 'checked in'}
          </span>
        ) : noShow ? (
          <span className="rounded-full bg-red-50 px-2.5 py-1 text-xs font-semibold text-red-700 ring-1 ring-inset ring-red-200">
            No show
          </span>
        ) : (
          <Button onClick={onCheckIn} busy={busy} disabled={!canCheckIn}>
            Check in
          </Button>
        )}
      </div>

      {!done && !canCheckIn ? (
        <p
          className={
            'border-t px-4 py-2 text-xs ' +
            (noShow ? 'border-red-100 bg-red-50/50 text-red-700' : 'border-slate-100 text-slate-500')
          }
        >
          {notOpenYet
            ? `Check-in opens around ${opens.toLocaleString()}, 24 hours before departure.`
            : noShow
              ? `No show — this passenger did not check in, and the check-in window closed at ${closes.toLocaleString()} (45 minutes before departure).`
              : `Check-in is not available for this passenger (${record.status.toLowerCase()}).`}
        </p>
      ) : null}

      {pass ? (
        <div className="border-t border-slate-100 p-4">
          <BoardingPassCard pass={pass} record={record} arrivalTime={flightArrivalTime} />
        </div>
      ) : null}
    </div>
  );
}
