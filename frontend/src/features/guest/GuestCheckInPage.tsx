import { useCallback, useEffect, useState, type FormEvent } from 'react';
import type { Booking } from '../../api/bookings';
import type { BoardingPass, CheckIn } from '../../api/checkin';
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

  return (
    <main className="mx-auto w-full max-w-3xl px-4 py-10">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">Your trip</h1>
          {flight ? (
            <p className="mt-1 text-sm text-slate-600">
              {flight.originAirportCode} → {flight.destinationAirportCode} ·{' '}
              {dayAndMonth(flight.departureTime)} {time(flight.departureTime)} ·{' '}
              {flight.flightNumber} · {booking?.bookingReference ?? flight.bookingReference}
            </p>
          ) : null}
        </div>
        <button
          type="button"
          onClick={handleDone}
          className="rounded-full border border-slate-300 bg-white px-5 py-2 text-sm font-semibold text-slate-700 transition hover:bg-slate-50"
        >
          Done
        </button>
      </div>

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
    <section className="rounded-2xl border border-slate-200 p-4">
      <div className="flex items-center justify-between gap-3">
        <div>
          <p className="font-semibold text-slate-900">{record.passengerName}</p>
          <p className="text-sm text-slate-500">
            {record.seatNumber ? `Seat ${record.seatNumber}` : 'Seat assigned at check-in'} ·{' '}
            {record.status.replace('_', ' ').toLowerCase()}
          </p>
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
