import { useCallback, useEffect, useState, type FormEvent } from 'react';
import type { Booking } from '../../api/bookings';
import type { BoardingPass, CheckIn } from '../../api/checkin';
import { flightsApi, type Flight } from '../../api/flights';
import { guestApi } from '../../api/guest';
import { Alert, ErrorAlert } from '../../components/Alert';
import { Button } from '../../components/Button';
import { Field } from '../../components/Field';
import { ApiError } from '../../lib/errors';
import { dayAndMonth, time } from '../../lib/format';
import { BoardingPassCard } from '../bookings/BoardingPassCard';
import { printBoardingPass } from '../bookings/printable';

/**
 * Guest check-in (GUEST_CHECKIN_MODULE.md §7) - the passenger whose ticket
 * an agency booked, who has no SkyBook account and needs to fly today.
 *
 * <p>Its own lean page, deliberately, rather than a second identity mode
 * inside BookingDetailPage: the owner-only actions (cancel, modify, payment)
 * are not hidden here, they are ABSENT, which is a property of the file
 * rather than of a conditional someone might later get wrong.
 *
 * <p>Everything after the lookup is id-based - the booking reference travels
 * in exactly one request body and never appears in a URL.
 */
export function GuestCheckInPage() {
  const [reference, setReference] = useState('');
  const [lastName, setLastName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const [bookingId, setBookingId] = useState<number | null>(null);
  const [booking, setBooking] = useState<Booking | null>(null);
  const [checkIns, setCheckIns] = useState<CheckIn[]>([]);
  const [passes, setPasses] = useState<Record<number, BoardingPass>>({});
  const [notice, setNotice] = useState<string | null>(null);
  /**
   * The flight behind the check-in rows. Fetched separately because a CheckIn
   * carries only its DEPARTURE - and a trip card showing where you leave but
   * not where you land is half a card. Flight schedules are public shopping
   * data (the gateway's PUBLIC_PATHS), so this needs no credential and cannot
   * fail the guest session; if it does not answer, the card simply shows the
   * departure it already had.
   */
  const [flightDetails, setFlightDetails] = useState<Flight | null>(null);

  const load = useCallback(async (id: number) => {
    // guestApi, not the signed-in page's clients: these calls must not carry
    // the global "401 → go to /sign-in" behaviour, which would strand a
    // passenger who has no account on a login screen (see api/guest.ts).
    const [bookingRecord, records] = await Promise.all([
      guestApi.booking(id),
      guestApi.checkIns(id),
    ]);
    setBooking(bookingRecord);
    setCheckIns(records);

    const issued = records.filter((r) => r.status === 'CHECKED_IN' || r.status === 'BOARDED');
    const fetched = await Promise.all(
      issued.map((r) => guestApi.boardingPass(r.id).then((p) => [r.id, p] as const).catch(() => null)),
    );
    setPasses(Object.fromEntries(fetched.filter(Boolean) as (readonly [number, BoardingPass])[]));

    const flightId = records[0]?.flightId;
    if (flightId) {
      flightsApi.byId(flightId).then(setFlightDetails).catch(() => setFlightDetails(null));
    }
  }, []);

  /** A lapsed or rejected guest session returns to the lookup form, never to /sign-in. */
  const endSessionLocally = useCallback((message: string) => {
    setBookingId(null);
    setBooking(null);
    setCheckIns([]);
    setPasses({});
    setNotice(message);
  }, []);

  /**
   * A guest session is short (30 minutes) and this page is exactly the thing
   * people leave open on an airport kiosk, so an expiry - or a Done on
   * another tab - must return to the lookup form rather than leave a stale
   * itinerary on screen. `pageshow` covers the back button restoring this
   * page from the browser's bfcache, where no network call would otherwise
   * happen at all.
   */
  useEffect(() => {
    function onPageShow(event: PageTransitionEvent) {
      if (event.persisted && bookingId !== null) {
        load(bookingId).catch(() =>
          endSessionLocally('That session ended — enter your details again.'));
      }
    }
    window.addEventListener('pageshow', onPageShow);
    return () => window.removeEventListener('pageshow', onPageShow);
  }, [bookingId, load, endSessionLocally]);

  async function handleLookup(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setNotice(null);
    setBusy(true);
    try {
      const session = await guestApi.start(reference, lastName);
      setBookingId(session.bookingId);
      await load(session.bookingId);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
    } finally {
      setBusy(false);
    }
  }

  async function handleCheckIn(checkInId: number) {
    setError(null);
    setBusy(true);
    try {
      await guestApi.checkIn(checkInId);
      if (bookingId !== null) await load(bookingId);
    } catch (cause) {
      if (cause instanceof ApiError && cause.kind === 'unauthenticated') {
        endSessionLocally('That session ended — enter your details again.');
        return;
      }
      setError(cause instanceof ApiError ? cause : null);
    } finally {
      setBusy(false);
    }
  }

  async function handleDone() {
    await guestApi.end().catch(() => {});
    setBookingId(null);
    setBooking(null);
    setCheckIns([]);
    setPasses({});
    setReference('');
    setLastName('');
    setNotice('You have been signed out of this booking.');
  }

  if (bookingId === null) {
    return (
      <main className="mx-auto w-full max-w-lg px-4 py-10">
        <h1 className="text-2xl font-bold tracking-tight text-slate-900">Check in</h1>
        <p className="mt-2 text-sm text-slate-600">
          Booked through a travel agent or company? Enter the details from your e-ticket — no
          SkyBook account needed.
        </p>

        <form onSubmit={handleLookup} noValidate className="mt-6 space-y-5">
          {notice ? <Alert>{notice}</Alert> : null}
          {/* One message for every wrong-input shape: the server refuses to
              say which part was wrong, and neither does this. */}
          {error?.kind === 'notFound' ? (
            <Alert>We couldn't find a booking matching those details.</Alert>
          ) : (
            <ErrorAlert error={error} />
          )}

          <Field
            label="Booking reference"
            value={reference}
            onChange={(e) => setReference(e.target.value)}
            autoComplete="off"
            required
          />
          <Field
            label="Last name"
            value={lastName}
            onChange={(e) => setLastName(e.target.value)}
            autoComplete="family-name"
            required
          />

          <Button type="submit" busy={busy} className="w-full">
            Find my booking
          </Button>
        </form>
      </main>
    );
  }

  const flight = checkIns[0];

  const bookingRef = booking?.bookingReference ?? flight?.bookingReference ?? "";

  return (
    <main className="mx-auto w-full max-w-3xl px-4 py-8 sm:py-10">
      <div className="flex items-center justify-between gap-3">
        <h1 className="text-2xl font-bold tracking-tight text-slate-900">Your trip</h1>
        <button
          type="button"
          onClick={handleDone}
          className="min-h-11 rounded-full border border-slate-300 bg-white px-5 text-sm font-semibold text-slate-700 transition hover:bg-slate-50"
        >
          Done
        </button>
      </div>

      {/*
        The trip, as a card rather than a sentence.

        This used to be one run-on line - "LHR → DXB · 8 Aug 08:25 · EK001 ·
        SBPVX6" - which is everything a passenger needs and nothing they can
        read at a glance. A boarding-pass-shaped card puts the reference where
        the eye goes first, the route in the middle at the size the times
        deserve, and the flight underneath. Same information, arranged the way
        an airline arranges it.
      */}
      {flight ? (
        <section className="mt-4 overflow-hidden rounded-2xl bg-brand-950 text-white shadow-[var(--shadow-float)]">
          <div className="flex flex-wrap items-start justify-between gap-3 px-5 pt-5 sm:px-6">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-[0.14em] text-white/50">
                Booking reference
              </p>
              <p className="tabular mt-1 text-2xl font-bold tracking-[0.16em] sm:text-3xl">
                {bookingRef}
              </p>
            </div>
            <span className="rounded-full bg-white/10 px-3 py-1 text-xs font-semibold text-white/85 ring-1 ring-inset ring-white/15">
              {dayAndMonth(flight.departureTime)}
            </span>
          </div>

          <div className="mt-5 flex items-center gap-3 px-5 pb-5 sm:gap-5 sm:px-6">
            <div className="min-w-0">
              <p className="tabular text-3xl font-semibold leading-none sm:text-4xl">
                {time(flight.departureTime)}
              </p>
              <p className="mt-1.5 text-sm font-semibold tracking-wide text-white/70">
                {flight.originAirportCode}
              </p>
            </div>

            {/* The connector: a plane between two dots, the airline idiom. */}
            <div className="flex flex-1 items-center gap-1.5 pb-5" aria-hidden="true">
              <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-white/40" />
              <span className="h-px flex-1 bg-white/25" />
              <svg viewBox="0 0 24 24" className="h-4 w-4 shrink-0 fill-accent-300">
                <path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z" />
              </svg>
              <span className="h-px flex-1 bg-white/25" />
              <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-white/40" />
            </div>

            <div className="min-w-0 text-right">
              <p className="tabular text-3xl font-semibold leading-none sm:text-4xl">
                {time(flightDetails?.arrivalTime ?? flight.departureTime)}
              </p>
              <p className="mt-1.5 text-sm font-semibold tracking-wide text-white/70">
                {flight.destinationAirportCode}
              </p>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-x-5 gap-y-1 border-t border-white/10 bg-white/[0.04] px-5 py-3 text-xs text-white/70 sm:px-6">
            <span>
              Flight <span className="tabular font-semibold text-white/90">{flight.flightNumber}</span>
            </span>
            <span>
              {checkIns.length} {checkIns.length === 1 ? 'passenger' : 'passengers'}
            </span>
            {flight.travelClass ? (
              <span className="capitalize">{flight.travelClass.replace('_', ' ').toLowerCase()}</span>
            ) : null}
          </div>
        </section>
      ) : null}

      <div className="mt-4">
        <ErrorAlert error={error} />
      </div>

      {checkIns.length === 0 ? (
        <p className="mt-6 rounded-xl bg-slate-50 px-4 py-3 text-sm text-slate-600">
          Check-in for this trip isn't open yet. It opens 24 hours before departure.
        </p>
      ) : (
        <div className="mt-6 space-y-4">
          {checkIns.map((record) => (
            <GuestPassengerRow
              key={record.id}
              record={record}
              pass={passes[record.id]}
              busy={busy}
              onCheckIn={() => handleCheckIn(record.id)}
            />
          ))}
        </div>
      )}
    </main>
  );
}

/** "Praveenreddy Somireddy" -> "PS". One letter when there is only one word. */
function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  const first = parts[0][0] ?? '';
  const last = parts.length > 1 ? parts[parts.length - 1][0] ?? '' : '';
  return (first + last).toUpperCase();
}

/**
 * Check-in state as a coloured pill rather than a lower-cased enum. The
 * words are the passenger's, not the schema's: NOT_OPEN is a time, not a
 * failure, and it should not read like one.
 */
function StatusPill({ status }: { status: CheckIn['status'] }) {
  const style =
    status === 'CHECKED_IN' || status === 'BOARDED'
      ? 'bg-emerald-50 text-emerald-700 ring-emerald-100'
      : status === 'OPEN'
        ? 'bg-sky-50 text-sky-700 ring-sky-100'
        : status === 'NO_SHOW' || status === 'CANCELLED'
          ? 'bg-red-50 text-red-600 ring-red-100'
          : 'bg-slate-100 text-slate-500 ring-slate-200';
  const label =
    status === 'CHECKED_IN'
      ? 'Checked in'
      : status === 'BOARDED'
        ? 'Boarded'
        : status === 'OPEN'
          ? 'Ready to check in'
          : status === 'NOT_OPEN'
            ? 'Opens 24h before'
            : status === 'NO_SHOW'
              ? 'Check-in closed'
              : status.replace('_', ' ').toLowerCase();
  return (
    <span className={'rounded-full px-2 py-0.5 text-[11px] font-semibold ring-1 ring-inset ' + style}>
      {label}
    </span>
  );
}

function GuestPassengerRow({
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
  const [email, setEmail] = useState('');
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState(false);
  const [emailError, setEmailError] = useState<ApiError | null>(null);

  async function handleEmail(event: FormEvent) {
    event.preventDefault();
    setEmailError(null);
    setSending(true);
    try {
      await guestApi.emailBoardingPass(record.id, email);
      setSent(true);
    } catch (cause) {
      setEmailError(cause instanceof ApiError ? cause : null);
    } finally {
      setSending(false);
    }
  }

  return (
    <section className="card p-4 sm:p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 items-start gap-3">
          {/* An initial disc: on a booking with three passengers, the eye
              finds the row it wants before it reads any of them. */}
          <span
            aria-hidden="true"
            className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-brand-50 text-sm font-bold text-brand-700 ring-1 ring-inset ring-brand-100"
          >
            {initials(record.passengerName)}
          </span>
          <div className="min-w-0">
            <p className="truncate font-semibold text-slate-900">{record.passengerName}</p>
            <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
              <StatusPill status={record.status} />
              <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-semibold text-slate-600">
                {record.seatNumber ? `Seat ${record.seatNumber}` : 'Seat at check-in'}
              </span>
              {record.travelClass ? (
                <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-semibold capitalize text-slate-600">
                  {record.travelClass.replace('_', ' ').toLowerCase()}
                </span>
              ) : null}
            </div>
          </div>
        </div>
        {record.status === 'OPEN' ? (
          <Button onClick={onCheckIn} busy={busy}>
            Check in
          </Button>
        ) : null}
      </div>

      {pass ? (
        <div className="mt-4 space-y-3">
          <BoardingPassCard pass={pass} record={record} />

          <div className="flex flex-wrap items-center gap-3">
            <button
              type="button"
              onClick={() => printBoardingPass(pass, record)}
              className="rounded-full border border-slate-300 bg-white px-5 py-2 text-sm font-semibold text-slate-700 transition hover:bg-slate-50"
            >
              Download pass
            </button>
          </div>

          {sent ? (
            <Alert>Your boarding pass is on its way.</Alert>
          ) : (
            <form onSubmit={handleEmail} noValidate className="flex flex-wrap items-end gap-3">
              <div className="min-w-[16rem] flex-1">
                <Field
                  label="Email my boarding pass to"
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  autoComplete="email"
                  required
                />
              </div>
              <Button type="submit" busy={sending}>
                Send
              </Button>
            </form>
          )}
          <ErrorAlert error={emailError} />
        </div>
      ) : null}
    </section>
  );
}
