import { useCallback, useEffect, useState } from 'react';
import { bookingsApi, type Booking, type CancellationPreview } from '../../api/bookings';
import { flightsApi, type Flight } from '../../api/flights';
import { FARE_TYPE_LABELS, TRAVEL_CLASS_LABELS } from '../../api/quotes';
import {
  checkInClosesAt,
  checkInOpensAt,
  checkinApi,
  type BoardingPass,
  type CheckIn,
} from '../../api/checkin';
import { seatsApi, type FlightSeatMap } from '../../api/seats';
import { Alert, ErrorAlert } from '../../components/Alert';
import { Button } from '../../components/Button';
import { ApiError } from '../../lib/errors';
import { dayAndMonth, durationFromMinutes, money, time } from '../../lib/format';
import { BoardingPassCard } from './BoardingPassCard';
import { StatusBadge } from './StatusBadge';
import { printETicket } from './printable';
import { ModifyBookingDialog } from './ModifyBookingDialog';

// Seeded fares are USD; the booking doesn't carry a currency of its own.
const CURRENCY = 'GBP';

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
  // Pre-check-in seat change: which passenger row has the dialog open.
  const [seatChangeFor, setSeatChangeFor] = useState<(typeof initial.passengers)[number] | null>(null);
  // Post-CHECK-IN seat change: which check-in record has the picker open.
  const [checkinSeatFor, setCheckinSeatFor] = useState<CheckIn | null>(null);
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
  // Live cancellation quote: fetched when the cancel panel opens, refreshed
  // every 30s while it stays open so the charges chart tracks the clock.
  const [preview, setPreview] = useState<CancellationPreview | null>(null);
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

  // Live cancellation quote while the cancel panel is open: fetch on open,
  // re-fetch every 30s - the server recomputes the tier from ITS clock, so
  // the chart can never promise a refund the cancel call would refuse.
  useEffect(() => {
    if (!managing) {
      setPreview(null);
      return;
    }
    const controller = new AbortController();
    const fetchPreview = () =>
      bookingsApi
        .cancellationPreview(booking.id, controller.signal)
        .then(setPreview)
        .catch(() => {});
    void fetchPreview();
    const timer = window.setInterval(fetchPreview, 30_000);
    return () => {
      controller.abort();
      window.clearInterval(timer);
    };
  }, [managing, booking.id]);

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

  /**
   * Whole-booking cancel - the one path that works even after web check-in:
   * the server voids every check-in and boarding pass downstream and releases
   * the seats. Refund per the live preview's tier.
   */
  async function runCancelEntire() {
    setCancelling(true);
    setError(null);
    try {
      await bookingsApi.cancel(booking.id);
      setSelected(new Set());
      setConfirm(null);
      setManaging(false);
      setRefundNotice(
        preview && !preview.unpaid
          ? Number(preview.refundAmount) > 0
            ? `Booking cancelled. A refund of ${money(preview.refundAmount, CURRENCY)} will be processed to your original payment method.`
            : 'Booking cancelled. Under the same-day cancellation policy no refund is due.'
          : 'Booking cancelled. Nothing had been charged.',
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
  /**
   * A segment is the RETURN only if its flight lands back at the journey's
   * first origin - a through-ticket's onward connection is a LEG of the
   * outbound, never a return, and must not offer return-only actions (the
   * server would refuse anyway; the button should not exist).
   */
  const isReturnSegment = (segmentIndex: number): boolean => {
    if (segmentIndex === 0) return false;
    const segs = booking.segments ?? [];
    const firstFlight = segmentFlights[segs[0]?.flightId ?? -1];
    const thisFlight = segmentFlights[segs.find((s) => s.segmentIndex === segmentIndex)?.flightId ?? -1];
    if (!firstFlight || !thisFlight) return false;
    return thisFlight.destinationAirportCode === firstFlight.originAirportCode;
  };

  const segLabel = (segmentIndex: number): string =>
    segmentIndex === 0 ? 'Outbound' : isReturnSegment(segmentIndex) ? 'Return' : `Leg ${segmentIndex + 1}`;

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
  const anyCheckedIn = activePassengers.some(checkedIn);
  // The per-passenger checklist only earns its place when there is an actual
  // choice: more than one traveller AND at least one of them individually
  // cancellable. A single-passenger booking (or an all-checked-in group) has
  // exactly one action - cancel the booking - so show only that.
  const canPickIndividuals = activePassengers.length > 1 && cancellablePassengers.length > 0;

  // The server's live quote can veto what the local heuristics would allow
  // (e.g. inside the 2h window) - trust it once it has loaded.
  const cancellationBlocked = preview !== null && !preview.allowed;

  // What the CURRENT selection would refund, priced from the live preview.
  // Traveller expansion mirrors the server: selecting any row cancels that
  // traveller off every segment, so all their rows count.
  const selectedQuote = (() => {
    if (!preview) return null;
    const travellerIds = new Set(
      activePassengers.filter((p) => selected.has(p.id)).map((p) => p.passengerId),
    );
    const rowIds = new Set(
      activePassengers.filter((p) => travellerIds.has(p.passengerId)).map((p) => p.id),
    );
    let paid = 0;
    let refund = 0;
    for (const line of preview.lines) {
      if (rowIds.has(line.bookingPassengerId)) {
        paid += Number(line.paid);
        refund += Number(line.refund);
      }
    }
    return { paid, refund };
  })();

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
                  {segLabel(seg.segmentIndex)}
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
                  {durationFromMinutes(segFlight.durationMinutes)}
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
                {isReturnSegment(seg.segmentIndex) ? (
                  <button
                    type="button"
                    disabled={cancelling}
                    onClick={() => void cancelReturnSegment(seg.segmentIndex)}
                    className="rounded-xl border border-red-200 px-3.5 py-1.5 text-xs font-semibold text-red-600 transition hover:bg-red-50 disabled:opacity-50"
                  >
                    Cancel the return &amp; refund
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
                        {segLabel(p.segmentIndex ?? 0)} ·{' '}
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
                <div className="flex items-center gap-3">
                  {booking.bookingStatus === 'CONFIRMED' && !p.cancelled && !checkedIn(p) && !departed && p.flightId ? (
                    <button
                      type="button"
                      onClick={() => setSeatChangeFor(p)}
                      className="rounded-full border border-slate-300 px-3 py-1 text-xs font-semibold text-slate-600 transition hover:border-accent-500 hover:text-accent-600"
                    >
                      Change seat
                    </button>
                  ) : null}
                  <div className="text-right">
                    <p className="tabular text-sm font-medium text-slate-900">{money(fare, CURRENCY)}</p>
                    {surcharge > 0 ? (
                      <p className="tabular text-[11px] text-slate-400">incl. seat {money(surcharge, CURRENCY)}</p>
                    ) : null}
                  </div>
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

      {seatChangeFor ? (
        <ChangeSeatDialog
          bookingId={booking.id}
          row={seatChangeFor}
          onClose={() => setSeatChangeFor(null)}
          onChanged={(updated) => {
            setBooking(updated);
            setSeatChangeFor(null);
          }}
        />
      ) : null}

      {/* Contact */}
      {booking.contact ? (
        <section className="card mt-5 px-5 py-4">
          <h2 className="text-xs font-semibold uppercase tracking-wide text-slate-500">Contact</h2>
          <div className="mt-3 grid gap-4 sm:grid-cols-3">
            <div>
              <p className="text-[10px] font-bold uppercase tracking-wide text-slate-400">Name</p>
              <p className="mt-0.5 text-sm font-semibold text-slate-900">{booking.contact.contactName}</p>
            </div>
            <div className="min-w-0">
              <p className="text-[10px] font-bold uppercase tracking-wide text-slate-400">Email</p>
              <p className="mt-0.5 truncate text-sm font-medium text-slate-700">{booking.contact.contactEmail}</p>
            </div>
            <div>
              <p className="text-[10px] font-bold uppercase tracking-wide text-slate-400">Phone</p>
              <p className={'tabular mt-0.5 text-sm ' + (booking.contact.contactPhone ? 'font-medium text-slate-700' : 'text-slate-400')}>
                {booking.contact.contactPhone ?? 'Not provided at booking'}
              </p>
            </div>
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
            <li>• <span className="font-medium">More than 72h before departure</span> — full refund per your fare rules (Saver keeps its 30% fee; Flexi and Premium refund in full).</li>
            <li>• <span className="font-medium">72h – 24h before departure</span> — 50% of the fare-rule refund.</li>
            <li>• <span className="font-medium">Under 24h (same day)</span> — cancellation still frees your seat, but the fare is not refunded.</li>
            <li>• <span className="font-medium">Last 2 hours before departure</span> — online cancellation is closed; the airport desk can still help.</li>
            <li>• <span className="font-medium">Already checked in?</span> — you can still cancel the whole booking; issued boarding passes are voided and the seats released.</li>
            <li>• A refund due is returned automatically to the original payment method; check-in closes for every passenger.</li>
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
        ) : activePassengers.length > 0 && !managing ? (
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
        ) : activePassengers.length > 0 ? (
          <div className="mt-4">
            {/* Live charges chart - what cancelling RIGHT NOW costs, straight
                from the server's clock, refreshed while this panel is open. */}
            <CancellationChargesCard preview={preview} />

            {/* Choose passengers to cancel (rule 12) - only when there is a
                real choice to make. One passenger = one action. */}
            {canPickIndividuals ? (
            <>
            <p className="mt-4 text-sm font-medium text-slate-700">Cancel passengers</p>
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
                Checked-in passengers can't be cancelled individually online — but{' '}
                <span className="font-semibold">Cancel entire booking</span> still works: their
                boarding passes are voided and every seat is released.
              </p>
            ) : null}
            </>
            ) : (
              <p className="mt-4 rounded-lg bg-slate-50 px-3 py-2 text-sm text-slate-600 ring-1 ring-inset ring-slate-200">
                {activePassengers.length === 1
                  ? 'This booking has one passenger, so cancelling means cancelling the whole booking.'
                  : 'All passengers have checked in, so they can only be cancelled together.'}
                {anyCheckedIn ? ' Issued boarding passes are voided and the seats released.' : ''}
              </p>
            )}

            <div className="mt-4 flex flex-wrap gap-2">
              {canPickIndividuals ? (
                <Button
                  onClick={() => setConfirm('selected')}
                  disabled={!canCancelSelected || cancelling || cancellationBlocked}
                >
                  Cancel selected passenger{selectedCount === 1 ? '' : 's'}
                  {selectedCount > 0 ? ` (${selectedCount})` : ''}
                </Button>
              ) : null}
              {/* Whole-booking cancel is always offered - it is the ONE path
                  that works after check-in too (the server voids the passes
                  and releases the seats). Only the time window blocks it. */}
              <button
                type="button"
                onClick={() => setConfirm('entire')}
                disabled={cancelling || cancellationBlocked}
                className="rounded-xl border border-red-200 px-4 py-2 text-sm font-semibold text-red-600 transition hover:bg-red-50 disabled:opacity-60"
              >
                {activePassengers.length === 1 ? 'Cancel booking' : 'Cancel entire booking'}
              </button>
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
                    ? activePassengers.length === 1
                      ? 'Cancel this booking?'
                      : 'Cancel the entire booking for all passengers?'
                    : `Cancel ${selectedCount} passenger${selectedCount === 1 ? '' : 's'}?`}
                </p>
                <p className="mt-1 text-sm text-red-700">
                  This can't be undone.{' '}
                  {confirm === 'entire' && preview && !preview.unpaid ? (
                    <>
                      You'll get{' '}
                      <span className="font-bold">{money(preview.refundAmount, CURRENCY)}</span> back
                      of the {money(preview.totalPaid, CURRENCY)} paid
                      {preview.refundPercent < 100 ? ` (${preview.refundPercent}% window)` : ''}.
                    </>
                  ) : confirm === 'selected' && selectedQuote && preview && !preview.unpaid ? (
                    <>
                      You'll get{' '}
                      <span className="font-bold">{money(selectedQuote.refund, CURRENCY)}</span> back
                      of their {money(selectedQuote.paid, CURRENCY)}
                      {preview.refundPercent < 100 ? ` (${preview.refundPercent}% window)` : ''}.
                    </>
                  ) : (
                    <>A refund is calculated per the rules above.</>
                  )}
                  {confirm === 'selected' ? ' The remaining passengers keep their seats and services.' : ''}
                  {confirm === 'entire' && anyCheckedIn
                    ? ' Boarding passes already issued will be voided and every seat released.'
                    : ''}
                </p>
                <div className="mt-3 flex gap-2">
                  <Button variant="secondary" onClick={() => setConfirm(null)} disabled={cancelling}>
                    Keep
                  </Button>
                  <button
                    type="button"
                    disabled={cancelling}
                    onClick={() =>
                      confirm === 'entire'
                        ? runCancelEntire()
                        : runCancel(
                            [...selected].filter((id) => cancellablePassengers.some((p) => p.id === id)),
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
                onChangeSeat={record.status === 'CHECKED_IN' ? () => setCheckinSeatFor(record) : undefined}
                flightArrivalTime={flight?.arrivalTime}
              />
            ))
          )}
        </div>
      </section>

      {/* Seat change AFTER check-in: same map and ceiling, but the move goes
          through checkin-service, which reissues the boarding pass. */}
      {checkinSeatFor
        ? (() => {
            const row = booking.passengers.find((p) => p.id === checkinSeatFor.bookingPassengerId);
            if (!row) return null;
            return (
              <ChangeSeatDialog
                bookingId={booking.id}
                row={{ ...row, seatNumber: checkinSeatFor.seatNumber ?? row.seatNumber }}
                onClose={() => setCheckinSeatFor(null)}
                onChanged={() => {}}
                onPick={async (seat) => {
                  await checkinApi.changeSeat(checkinSeatFor.id, seat);
                  // Fresh pass (reissued with the new seat) + fresh booking.
                  try {
                    const pass = await checkinApi.boardingPass(checkinSeatFor.id);
                    setPasses((current) => ({ ...current, [checkinSeatFor.id]: pass }));
                  } catch {
                    // The reissued pass will arrive with the next load.
                  }
                  setCheckinSeatFor(null);
                  await load();
                }}
              />
            );
          })()
        : null}

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
  onChangeSeat,
  flightArrivalTime,
}: {
  record: CheckIn;
  pass?: BoardingPass;
  busy: boolean;
  onCheckIn: () => void;
  /** Present only while a seat change is still possible (CHECKED_IN, pre-boarding). */
  onChangeSeat?: () => void;
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
      <div className="flex items-center justify-between gap-4 px-5 py-4">
        <div className="min-w-0">
          <p className="text-[15px] font-bold tracking-tight text-slate-900">{record.passengerName}</p>
          <p className="mt-2 flex flex-wrap items-center gap-1.5 text-sm text-slate-600">
            <span className="tabular rounded-md bg-slate-100 px-2 py-0.5 font-mono text-xs font-bold text-slate-700">
              {record.flightNumber}
            </span>
            <span className="tabular font-semibold text-slate-800">
              {record.originAirportCode}
              <span className="mx-1 text-brand-600">→</span>
              {record.destinationAirportCode}
            </span>
            <span className="text-slate-300">·</span>
            <span className="tabular text-slate-500">
              {dayAndMonth(record.departureTime)}, {time(record.departureTime)}
            </span>
            {record.seatNumber ? (
              <span className="tabular rounded-md bg-brand-50 px-2 py-0.5 text-xs font-bold text-brand-800 ring-1 ring-inset ring-brand-100">
                Seat {record.seatNumber}
              </span>
            ) : null}
          </p>
        </div>

        {done ? (
          <span className="flex items-center gap-2">
            {onChangeSeat ? (
              <button
                type="button"
                onClick={onChangeSeat}
                className="rounded-full border border-slate-300 px-3 py-1 text-xs font-semibold text-slate-600 transition hover:border-accent-500 hover:text-accent-600"
              >
                Change seat
              </button>
            ) : null}
            <span className="rounded-full bg-emerald-50 px-2.5 py-1 text-xs font-medium text-emerald-700 ring-1 ring-inset ring-emerald-200">
              {record.status === 'BOARDED' ? 'boarded' : 'checked in'}
            </span>
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
            'flex items-center gap-1.5 border-t px-5 py-2.5 text-xs ' +
            (noShow ? 'border-red-100 bg-red-50/50 text-red-700' : 'border-slate-100 bg-slate-50/60 text-slate-500')
          }
        >
          {notOpenYet ? (
            <>
              <span aria-hidden="true">🕐</span>
              <span>
                Check-in opens{' '}
                <span className="tabular font-semibold text-slate-700">
                  {dayAndMonth(opens.toISOString())}, {time(opens.toISOString())}
                </span>{' '}
                — 24 hours before departure.
              </span>
            </>
          ) : noShow ? (
            <span>
              No show — this passenger did not check in before the window closed at{' '}
              {dayAndMonth(closes.toISOString())}, {time(closes.toISOString())} (45 minutes before
              departure).
            </span>
          ) : (
            <span>Check-in is not available for this passenger ({record.status.toLowerCase()}).</span>
          )}
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

/**
 * Pre-check-in seat change (passenger features): the row's flight's real seat
 * map in the traveller's cabin; taken seats are disabled, and for Saver so is
 * anything above the surcharge they originally paid - the SAME entitlement
 * ceiling check-in applies, enforced again server-side.
 */
function ChangeSeatDialog({ bookingId, row, onClose, onChanged, onPick }: {
  bookingId: number;
  row: Booking['passengers'][number];
  onClose: () => void;
  onChanged: (updated: Booking) => void;
  /**
   * Override for the post-CHECK-IN path: the seat moves through
   * checkin-service (which reissues the boarding pass) instead of
   * booking-service. Same map, same ceiling, different authority.
   */
  onPick?: (seat: string) => Promise<void>;
}) {
  const [map, setMap] = useState<FlightSeatMap | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    seatsApi.forFlight(row.flightId!, controller.signal).then(setMap).catch((e) => {
      if (!(e instanceof DOMException)) setError(e instanceof ApiError ? e : null);
    });
    return () => controller.abort();
  }, [row.flightId]);

  const paid = Number(row.seatSurcharge) || 0;
  const saver = row.fareType === 'SAVER';
  // After CHECK-IN (onPick path) the ceiling applies to EVERY fare: the seat
  // must list at or below the surcharge already paid - checkin-service's
  // contained seat-change rule. Pre-check-in, only Saver has a ceiling.
  const ceilingApplies = onPick ? true : saver;

  async function pick(seat: string) {
    setBusy(true);
    setError(null);
    try {
      if (onPick) {
        await onPick(seat);
      } else {
        onChanged(await bookingsApi.changeSeat(bookingId, row.id, seat));
      }
    } catch (e) {
      setError(e instanceof ApiError ? e : null);
      setBusy(false);
    }
  }

  return (
    <div className="fixed inset-0 z-30 grid place-items-center bg-slate-900/50 p-4" onClick={onClose}>
      <div className="max-h-[80vh] w-full max-w-lg overflow-y-auto rounded-2xl bg-white p-5 shadow-xl"
        onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-bold text-slate-900">
            Change seat — {row.firstName} {row.lastName}
            <span className="ml-2 text-xs font-medium text-slate-500">
              current {row.seatNumber ?? '—'}
            </span>
          </h3>
          <button type="button" onClick={onClose} className="text-xs font-medium text-slate-500 hover:text-slate-700">Close</button>
        </div>
        {onPick ? (
          <p className="mt-1 text-xs text-slate-500">
            After check-in you can move to seats listing at or below what you already paid
            {paid > 0 ? ` (${money(paid, CURRENCY)})` : ' (free seats)'} — pricier seats are an
            upgrade for the airport desk. Your boarding pass is reissued with the new seat.
          </p>
        ) : saver ? (
          <p className="mt-1 text-xs text-slate-500">
            Saver fare: seats up to the {money(paid, CURRENCY)} surcharge you already paid.
          </p>
        ) : (
          <p className="mt-1 text-xs text-slate-500">Your fare includes free seat selection — pick any open seat.</p>
        )}
        <div className="mt-3"><ErrorAlert error={error} /></div>
        {!map ? (
          <p className="mt-3 text-sm text-slate-500">Loading seat map…</p>
        ) : (
          <div className="mt-3 flex flex-wrap gap-1.5">
            {map.aircraft.seats
              .filter((s) => s.seatType === row.travelClass && s.status === 'ACTIVE')
              .map((s) => {
                const taken = map.taken.has(s.seatNumber) && s.seatNumber !== row.seatNumber;
                const overCeiling = ceilingApplies && Number(s.listedSurcharge) > paid;
                const current = s.seatNumber === row.seatNumber;
                const disabled = taken || overCeiling || current || busy;
                return (
                  <button key={s.seatNumber} type="button" disabled={disabled}
                    onClick={() => void pick(s.seatNumber)}
                    title={taken ? 'Taken' : overCeiling ? `Surcharge ${money(Number(s.listedSurcharge), CURRENCY)} — above your ceiling` : Number(s.listedSurcharge) > 0 ? `Listed surcharge ${money(Number(s.listedSurcharge), CURRENCY)}` : 'Free'}
                    className={
                      'tabular grid h-9 w-11 place-items-center rounded-lg text-xs font-bold transition ' +
                      (current
                        ? 'bg-brand-900 text-white'
                        : disabled
                          ? 'bg-slate-100 text-slate-300'
                          : 'bg-emerald-50 text-emerald-800 ring-1 ring-inset ring-emerald-200 hover:bg-emerald-500 hover:text-white')
                    }>
                    {s.seatNumber}
                  </button>
                );
              })}
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * The live cancellation charges chart: the four time tiers with the tier in
 * force highlighted, a ticking countdown to the next drop, and exactly what
 * cancelling right now pays back. Data comes from the server's clock
 * (/cancellation-preview, refreshed by the parent) - this component only
 * renders it and ticks the countdown.
 */
function CancellationChargesCard({ preview }: { preview: CancellationPreview | null }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  if (!preview) {
    return (
      <p className="rounded-xl bg-slate-50 px-4 py-3 text-sm text-slate-500">
        Checking today's cancellation terms…
      </p>
    );
  }

  const boundary = (iso?: string | null) => (iso ? new Date(iso).getTime() : null);
  const closes = boundary(preview.cancelClosesAt);
  const full = boundary(preview.fullRefundUntil);
  const half = boundary(preview.halfRefundUntil);

  const fmt = (iso?: string | null) =>
    iso ? `${dayAndMonth(iso)} ${time(iso)}` : '—';

  const countdown = (target: number) => {
    let seconds = Math.max(0, Math.floor((target - now) / 1000));
    const days = Math.floor(seconds / 86400);
    seconds -= days * 86400;
    const hours = Math.floor(seconds / 3600);
    seconds -= hours * 3600;
    const minutes = Math.floor(seconds / 60);
    seconds -= minutes * 60;
    return days > 0
      ? `${days}d ${hours}h ${minutes}m`
      : hours > 0
        ? `${hours}h ${minutes}m ${seconds}s`
        : `${minutes}m ${seconds}s`;
  };

  // Which band is in force, and what the next deadline means for the money.
  const closed = !preview.allowed;
  const tiers = [
    { label: '100% refund', window: `Until ${fmt(preview.fullRefundUntil)}`, active: !closed && preview.refundPercent === 100 },
    { label: '50% refund', window: `Until ${fmt(preview.halfRefundUntil)}`, active: !closed && preview.refundPercent === 50 },
    { label: 'No refund', window: `Until ${fmt(preview.cancelClosesAt)}`, active: !closed && preview.refundPercent === 0 },
    { label: 'Online cancel closed', window: `From ${fmt(preview.cancelClosesAt)}`, active: closed },
  ];

  const nextDeadline = !closed
    ? preview.refundPercent === 100 && full && full > now
      ? { at: full, note: 'refund drops to 50% in' }
      : preview.refundPercent === 50 && half && half > now
        ? { at: half, note: 'refund drops to zero in' }
        : closes && closes > now
          ? { at: closes, note: 'online cancellation closes in' }
          : null
    : null;

  return (
    <div className="rounded-xl border border-slate-200 p-4">
      <p className="text-xs font-semibold uppercase tracking-wide text-slate-500">
        Cancellation charges right now
      </p>

      {/* Tier chart: four bands, the one in force highlighted. */}
      <div className="mt-3 grid grid-cols-2 gap-1.5 sm:grid-cols-4">
        {tiers.map((tier) => (
          <div
            key={tier.label}
            className={
              'rounded-lg px-2.5 py-2 text-center ring-1 ring-inset ' +
              (tier.active
                ? tier.label === 'Online cancel closed'
                  ? 'bg-red-50 text-red-700 ring-red-200'
                  : 'bg-brand-900 text-white ring-brand-900'
                : 'bg-slate-50 text-slate-500 ring-slate-200')
            }
          >
            <p className="text-xs font-bold">{tier.label}</p>
            <p className={'tabular mt-0.5 text-[10px] ' + (tier.active ? 'opacity-80' : 'text-slate-400')}>
              {tier.window}
            </p>
          </div>
        ))}
      </div>

      {nextDeadline ? (
        <p className="tabular mt-2 text-xs font-semibold text-amber-700">
          ⏱ Your {nextDeadline.note} {countdown(nextDeadline.at)}
        </p>
      ) : null}

      {preview.unpaid ? (
        <p className="mt-3 rounded-lg bg-emerald-50 px-3 py-2 text-sm text-emerald-800 ring-1 ring-inset ring-emerald-200">
          Nothing has been charged on this booking yet — cancelling is free.
        </p>
      ) : (
        <dl className="mt-3 space-y-1 text-sm">
          <div className="flex justify-between text-slate-600">
            <dt>You paid</dt>
            <dd className="tabular">{money(preview.totalPaid, CURRENCY)}</dd>
          </div>
          {Number(preview.fareRuleFee) > 0 ? (
            <div className="flex justify-between text-slate-600">
              <dt>Saver fare-rule fee</dt>
              <dd className="tabular">−{money(preview.fareRuleFee, CURRENCY)}</dd>
            </div>
          ) : null}
          {Number(preview.timePenalty) > 0 ? (
            <div className="flex justify-between text-slate-600">
              <dt>Time-of-cancellation charge ({100 - preview.refundPercent}%)</dt>
              <dd className="tabular">−{money(preview.timePenalty, CURRENCY)}</dd>
            </div>
          ) : null}
          <div className="flex justify-between border-t border-slate-200 pt-1 font-bold text-slate-900">
            <dt>You'd get back</dt>
            <dd className={'tabular ' + (Number(preview.refundAmount) > 0 ? 'text-emerald-700' : 'text-red-600')}>
              {money(preview.refundAmount, CURRENCY)}
            </dd>
          </div>
        </dl>
      )}

      {closed && preview.blockedReason ? (
        <p className="mt-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700 ring-1 ring-inset ring-red-200">
          {preview.blockedReason}
        </p>
      ) : null}
    </div>
  );
}
