import { useEffect, useMemo, useState } from 'react';
import { bookingsApi, type Booking, type BookingPassenger, type PassengerDetail } from '../../api/bookings';
import { flightsApi, type Flight } from '../../api/flights';
import { paymentsApi, type Payment } from '../../api/payments';
import { quotesApi, type Quote } from '../../api/quotes';
import { Alert, ErrorAlert } from '../../components/Alert';
import { Button } from '../../components/Button';
import { ApiError } from '../../lib/errors';
import { addDaysIso, dayAndMonth, money, time, todayIso } from '../../lib/format';

const EXTRA_BAG_FEE = 40;
const CURRENCY = 'USD';

/**
 * Modify a booking: change flight/date and/or bags, as a guided REBOOK - the
 * only honest mechanics the platform has. A new booking is created at TODAY's
 * fares (each passenger keeps their cabin and fare type), paid, and the old
 * booking is then cancelled with its refund computed per its fare rules. The
 * dialog says exactly that before asking for confirmation; seats are not
 * carried over (different aircraft, different map) and are re-chosen free at
 * check-in.
 *
 * <p>Ordering is deliberate: pay the NEW booking first, cancel the old only
 * after - a payment failure leaves the original untouched. If cancelling the
 * old fails after the new one is paid, both exist and the dialog says which
 * one to cancel from My trips - never silently.
 */
export function ModifyBookingDialog({
  booking,
  currentFlight,
  onClose,
  onRebooked,
}: {
  booking: Booking;
  currentFlight: Flight | null;
  onClose: () => void;
  /** Called with the NEW booking once the swap completed. */
  onRebooked: (newBooking: Booking) => void;
}) {
  const activePassengers = useMemo(
    () => booking.passengers.filter((p) => !p.cancelled),
    [booking.passengers],
  );

  const [date, setDate] = useState(
    () => currentFlight?.departureTime.slice(0, 10) ?? addDaysIso(todayIso(), 1),
  );
  const [flights, setFlights] = useState<Flight[] | null>(null);
  const [chosen, setChosen] = useState<Flight | null>(null);
  const [quote, setQuote] = useState<Quote | null>(null);
  const [bags, setBags] = useState<number[]>(() => activePassengers.map((p) => p.extraBags ?? 0));
  const [error, setError] = useState<ApiError | null>(null);
  const [stage, setStage] = useState<
    'pick' | 'review' | 'booking' | 'paying' | 'cancellingOld' | 'oldCancelFailed'
  >('pick');
  const [newBooking, setNewBooking] = useState<Booking | null>(null);

  const origin = currentFlight?.originAirportCode;
  const destination = currentFlight?.destinationAirportCode;

  // Flights for the picked date on the SAME route.
  useEffect(() => {
    if (!origin || !destination) {
      return;
    }
    const controller = new AbortController();
    setFlights(null);
    setChosen(null);
    flightsApi
      .search({ origin, destination, date }, controller.signal)
      .then((list) => setFlights(list.filter((f) => f.status !== 'CANCELLED')))
      .catch(() => setFlights([]));
    return () => controller.abort();
  }, [origin, destination, date]);

  // Reprice once a flight is chosen - the same quote checkout will honour.
  useEffect(() => {
    if (!chosen) {
      setQuote(null);
      return;
    }
    const controller = new AbortController();
    quotesApi
      .forFlight(chosen.id, controller.signal)
      .then(setQuote)
      .catch(() => setQuote(null));
    return () => controller.abort();
  }, [chosen]);

  function fareFor(passenger: BookingPassenger): number {
    const cabin = quote?.cabins.find((c) => c.travelClass === passenger.travelClass);
    return Number(cabin?.baseFares[passenger.fareType] ?? 0);
  }

  const bagTotal = bags.reduce((sum, b) => sum + b, 0) * EXTRA_BAG_FEE;
  const newTotal = activePassengers.reduce((sum, p) => sum + fareFor(p), 0) + bagTotal;

  async function confirmRebook() {
    if (!chosen) {
      return;
    }
    setError(null);
    try {
      setStage('booking');
      const passengers: PassengerDetail[] = activePassengers.map((p, i) => ({
        title: p.title ?? undefined,
        firstName: p.firstName,
        lastName: p.lastName,
        dob: p.dob ?? '',
        gender: p.gender ?? undefined,
        nationality: p.nationality ?? '',
        passportNumber: p.passportNumber ?? '',
        passportExpiry: p.passportExpiry ?? '',
        travelClass: p.travelClass,
        fareType: p.fareType,
        ...(bags[i] > 0 ? { extraBags: bags[i] } : {}),
      }));
      const created = await bookingsApi.create({
        flightId: chosen.id,
        passengers,
        contact: booking.contact
          ? { contactName: booking.contact.contactName, contactEmail: booking.contact.contactEmail }
          : { contactName: `${activePassengers[0].firstName} ${activePassengers[0].lastName}`, contactEmail: '' },
      });
      setNewBooking(created);

      setStage('paying');
      const payment = await waitForPayment(created.id);
      const authorized = await paymentsApi.authorize(payment.id);
      await paymentsApi.capture(authorized.id);

      setStage('cancellingOld');
      try {
        await bookingsApi.cancel(booking.id);
      } catch {
        setStage('oldCancelFailed');
        return;
      }
      onRebooked(created);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
      setStage(newBooking ? 'review' : 'pick');
    }
  }

  const busy = stage === 'booking' || stage === 'paying' || stage === 'cancellingOld';

  return (
    <div className="fixed inset-0 z-40 flex items-start justify-center overflow-y-auto bg-slate-900/50 p-4 sm:p-8">
      <div className="w-full max-w-2xl rounded-2xl bg-white p-5 shadow-[var(--shadow-float)] sm:p-7">
        <div className="flex items-start justify-between gap-4">
          <div>
            <h2 className="text-xl font-bold tracking-tight text-slate-900">Modify booking</h2>
            <p className="mt-1 text-sm text-slate-500">
              {origin} → {destination} · change the flight, the date, or your bags.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={busy}
            aria-label="Close"
            className="grid h-8 w-8 place-items-center rounded-full text-slate-400 transition hover:bg-slate-100 hover:text-slate-700 disabled:opacity-40"
          >
            <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true">
              <path d="M19 6.4 17.6 5 12 10.6 6.4 5 5 6.4 10.6 12 5 17.6 6.4 19 12 13.4 17.6 19 19 17.6 13.4 12z" />
            </svg>
          </button>
        </div>

        <div className="mt-4">
          <ErrorAlert error={error} />
        </div>

        {/* How a change actually works - shown up front, not in small print. */}
        <div className="mt-3 rounded-xl bg-amber-50 px-4 py-3 text-xs leading-relaxed text-amber-900 ring-1 ring-inset ring-amber-200">
          Changes work as a rebooking: a new booking is created at today's fares and your current one
          is cancelled with a refund per its fare rules. Chosen seats are not carried over — seats on
          the new flight are assigned free at check-in.
        </div>

        {stage === 'oldCancelFailed' && newBooking ? (
          <div className="mt-4 space-y-3">
            <Alert tone="warning">
              Your new booking {newBooking.bookingReference} is paid and confirmed, but the original
              ({booking.bookingReference}) could not be cancelled automatically. Please cancel it
              from My trips to receive its refund.
            </Alert>
            <div className="flex justify-end">
              <Button onClick={() => onRebooked(newBooking)}>View new booking</Button>
            </div>
          </div>
        ) : (
          <>
            {/* Date + flight pick. */}
            <div className="mt-4 grid gap-3 sm:grid-cols-[auto_1fr]">
              <label className="text-sm">
                <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                  New date
                </span>
                <input
                  type="date"
                  value={date}
                  min={todayIso()}
                  disabled={busy}
                  onChange={(e) => setDate(e.target.value)}
                  className="tabular rounded-xl border border-slate-300 bg-white px-3.5 py-2.5 text-sm outline-none transition focus:border-brand-500 focus:ring-4 focus:ring-brand-500/15"
                />
              </label>
              <div className="min-w-0">
                <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                  Flights on {dayAndMonth(`${date}T00:00`)}
                </span>
                {flights === null ? (
                  <p className="rounded-xl bg-slate-50 px-3 py-2.5 text-sm text-slate-500">Looking up flights…</p>
                ) : flights.length === 0 ? (
                  <p className="rounded-xl bg-slate-50 px-3 py-2.5 text-sm text-slate-500">
                    No flights on this route that day — try another date.
                  </p>
                ) : (
                  <div className="space-y-1.5">
                    {flights.map((f) => {
                      const isCurrent = currentFlight?.id === f.id;
                      const active = chosen?.id === f.id;
                      return (
                        <button
                          key={f.id}
                          type="button"
                          disabled={busy}
                          onClick={() => setChosen(f)}
                          aria-pressed={active}
                          className={
                            'tabular flex w-full items-center justify-between gap-3 rounded-xl border px-3.5 py-2.5 text-left text-sm transition ' +
                            (active
                              ? 'border-brand-900 ring-1 ring-brand-900'
                              : 'border-slate-200 hover:border-slate-400')
                          }
                        >
                          <span className="font-semibold text-slate-900">
                            {time(f.departureTime)} – {time(f.arrivalTime)}
                          </span>
                          <span className="text-slate-500">{f.flightNumber}</span>
                          {isCurrent ? (
                            <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-bold text-slate-500">
                              Current flight
                            </span>
                          ) : null}
                        </button>
                      );
                    })}
                  </div>
                )}
              </div>
            </div>

            {/* Bags per passenger. */}
            <div className="mt-4">
              <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                Extra bags ({money(EXTRA_BAG_FEE, CURRENCY)} each)
              </span>
              <div className="space-y-1.5">
                {activePassengers.map((p, i) => (
                  <div
                    key={p.id}
                    className="flex items-center justify-between gap-3 rounded-xl bg-slate-50 px-3.5 py-2"
                  >
                    <span className="text-sm font-semibold text-slate-800">
                      {p.firstName} {p.lastName}
                    </span>
                    <span className="flex items-center gap-2">
                      <button
                        type="button"
                        aria-label={`Fewer bags for ${p.firstName}`}
                        disabled={busy || bags[i] <= 0}
                        onClick={() => setBags((prev) => prev.map((b, j) => (j === i ? b - 1 : b)))}
                        className="grid h-8 w-8 place-items-center rounded-lg bg-slate-200 font-semibold text-slate-600 transition hover:bg-slate-300 disabled:cursor-not-allowed disabled:bg-slate-100 disabled:text-slate-300"
                      >
                        −
                      </button>
                      <span className="tabular grid h-8 w-10 place-items-center rounded-lg border border-slate-700 bg-white text-sm font-bold">
                        {bags[i]}
                      </span>
                      <button
                        type="button"
                        aria-label={`More bags for ${p.firstName}`}
                        disabled={busy || bags[i] >= 5}
                        onClick={() => setBags((prev) => prev.map((b, j) => (j === i ? b + 1 : b)))}
                        className="grid h-8 w-8 place-items-center rounded-lg bg-brand-900 font-semibold text-white transition hover:bg-brand-800 disabled:cursor-not-allowed disabled:bg-slate-200 disabled:text-slate-400"
                      >
                        +
                      </button>
                    </span>
                  </div>
                ))}
              </div>
            </div>

            {/* Repriced total. */}
            {chosen && quote ? (
              <dl className="mt-4 rounded-xl border border-slate-200 text-sm">
                {activePassengers.map((p, i) => (
                  <div key={p.id} className="flex justify-between border-b border-slate-100 px-4 py-2">
                    <dt className="text-slate-600">
                      {p.firstName} {p.lastName} · {p.travelClass.replace('_', ' ').toLowerCase()} ·{' '}
                      {p.fareType.toLowerCase()}
                      {bags[i] > 0 ? ` · ${bags[i]} bag${bags[i] > 1 ? 's' : ''}` : ''}
                    </dt>
                    <dd className="tabular font-semibold text-slate-900">
                      {money(fareFor(p) + bags[i] * EXTRA_BAG_FEE, CURRENCY)}
                    </dd>
                  </div>
                ))}
                <div className="flex justify-between px-4 py-2 font-bold">
                  <dt className="text-slate-900">New booking total (today's fares)</dt>
                  <dd className="tabular text-slate-900">{money(newTotal, CURRENCY)}</dd>
                </div>
              </dl>
            ) : null}

            {stage === 'booking' ? <div className="mt-3"><Alert tone="info">Creating your new booking…</Alert></div> : null}
            {stage === 'paying' ? <div className="mt-3"><Alert tone="info">Taking payment for the new booking…</Alert></div> : null}
            {stage === 'cancellingOld' ? (
              <div className="mt-3"><Alert tone="info">Cancelling your original booking and arranging its refund…</Alert></div>
            ) : null}

            <div className="mt-5 flex items-center justify-between">
              <button
                type="button"
                onClick={onClose}
                disabled={busy}
                className="rounded-full border border-slate-300 bg-white px-6 py-2.5 text-sm font-semibold text-slate-700 transition hover:bg-slate-50 disabled:opacity-50"
              >
                Keep current booking
              </button>
              <Button onClick={confirmRebook} busy={busy} disabled={!chosen || !quote}>
                Confirm change · pay {chosen && quote ? money(newTotal, CURRENCY) : ''}
              </Button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

async function waitForPayment(bookingId: number): Promise<Payment> {
  const delays = [400, 700, 1000, 1500, 2000, 3000, 4000, 5000];
  for (const delay of delays) {
    const payment = await paymentsApi.forBooking(bookingId);
    if (payment) {
      return payment;
    }
    await new Promise((resolve) => setTimeout(resolve, delay));
  }
  const last = await paymentsApi.forBooking(bookingId);
  if (last) {
    return last;
  }
  throw new ApiError(
    'unavailable',
    0,
    'The new booking was created, but its payment could not be started. Open it from My bookings to pay - your original booking is unchanged.',
  );
}
