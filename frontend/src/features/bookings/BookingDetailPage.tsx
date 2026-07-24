import { useCallback, useEffect, useState } from 'react';
import { bookingsApi, type Booking } from '../../api/bookings';
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
import { money } from '../../lib/format';
import { BoardingPassCard } from './BoardingPassCard';

/**
 * One booking, with check-in (FRONTEND_MODULE.md §5 screens 8-9).
 *
 * <p>Check-in records arrive asynchronously - checkin-service creates one per
 * passenger after consuming the CONFIRMED event - so a just-confirmed booking
 * may briefly have none. That is shown as "preparing", not as an error.
 */
export function BookingDetailPage({
  booking: initial,
  onBack,
}: {
  booking: Booking;
  onBack: () => void;
}) {
  const [booking, setBooking] = useState(initial);
  const [checkIns, setCheckIns] = useState<CheckIn[] | null>(null);
  const [passes, setPasses] = useState<Record<number, BoardingPass>>({});
  const [error, setError] = useState<ApiError | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  const load = useCallback(async (signal?: AbortSignal) => {
    try {
      const [fresh, records] = await Promise.all([
        bookingsApi.byId(initial.id, signal),
        checkinApi.forBooking(initial.id, signal),
      ]);
      setBooking(fresh);
      setCheckIns(records);

      // Fetch any boarding passes that already exist, so a returning passenger
      // sees their pass without having to check in again.
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
      // A 409 here is the window being shut, and the server's message says
      // exactly when it opens - far more useful than anything invented here.
      setError(cause instanceof ApiError ? cause : null);
    } finally {
      setBusyId(null);
    }
  }

  return (
    <main className="mx-auto max-w-3xl px-6 py-10">
      <button type="button" onClick={onBack} className="text-sm font-medium text-brand-700 hover:underline">
        ← All bookings
      </button>

      <h1 className="mt-4 font-mono text-2xl font-semibold tracking-[0.15em] text-brand-700">
        {booking.bookingReference}
      </h1>
      <p className="mt-1 text-sm text-slate-600">
        {booking.bookingStatus.toLowerCase()} · {money(booking.totalFare, 'USD')}
      </p>

      <div className="mt-6 space-y-4">
        <ErrorAlert error={error} />

        {checkIns === null ? (
          <p className="text-sm text-slate-500">Loading…</p>
        ) : checkIns.length === 0 ? (
          <Alert tone="info">
            {booking.bookingStatus === 'CONFIRMED'
              ? 'Preparing check-in for this booking — this usually takes a few seconds.'
              : 'Check-in becomes available once your booking is confirmed.'}
          </Alert>
        ) : (
          checkIns.map((record) => (
            <CheckInRow
              key={record.id}
              record={record}
              pass={passes[record.id]}
              busy={busyId === record.id}
              onCheckIn={() => handleCheckIn(record)}
            />
          ))
        )}
      </div>
    </main>
  );
}

function CheckInRow({
  record,
  pass,
  busy,
  onCheckIn,
}: {
  record: CheckIn;
  pass?: BoardingPass;
  busy: boolean;
  onCheckIn: () => void;
}) {
  // Gate on the SERVER's status, never on a locally recomputed window. The
  // window is server configuration (the e2e profile widens it deliberately), so
  // deriving "too early" from a hardcoded 24 hours would disable a button the
  // server would happily accept. The times below are only used to EXPLAIN a
  // NOT_OPEN, never to decide it.
  const done = record.status === 'CHECKED_IN' || record.status === 'BOARDED';
  const canCheckIn = record.status === 'OPEN';
  const notOpenYet = record.status === 'NOT_OPEN';
  const opens = checkInOpensAt(record.departureTime);
  const closes = checkInClosesAt(record.departureTime);

  return (
    <div className="rounded border border-slate-200 bg-white">
      <div className="flex items-center justify-between gap-4 px-4 py-3">
        <div>
          <p className="font-medium text-slate-900">{record.passengerName}</p>
          <p className="text-sm text-slate-600">
            {record.flightNumber} · {record.originAirportCode} → {record.destinationAirportCode}
            {record.seatNumber ? ` · seat ${record.seatNumber}` : ''}
          </p>
        </div>

        {done ? (
          <span className="rounded bg-emerald-50 px-2 py-0.5 text-xs font-medium text-emerald-700 ring-1 ring-inset ring-emerald-200">
            {record.status === 'BOARDED' ? 'boarded' : 'checked in'}
          </span>
        ) : (
          <Button onClick={onCheckIn} busy={busy} disabled={!canCheckIn}>
            Check in
          </Button>
        )}
      </div>

      {/*
        Explain WHY the control is unavailable rather than leaving a dead button.
        A passenger who cannot see the reason will simply press it and collect a
        409 they have no way to interpret.
      */}
      {!done && !canCheckIn ? (
        <p className="border-t border-slate-100 px-4 py-2 text-xs text-slate-500">
          {notOpenYet
            ? `Check-in opens around ${opens.toLocaleString()}, 24 hours before departure.`
            : record.status === 'NO_SHOW'
              ? `Check-in closed at ${closes.toLocaleString()}, 45 minutes before departure.`
              : `Check-in is not available for this passenger (${record.status.toLowerCase()}).`}
        </p>
      ) : null}

      {pass ? (
        <div className="border-t border-slate-100 p-4">
          <BoardingPassCard pass={pass} />
        </div>
      ) : null}
    </div>
  );
}
