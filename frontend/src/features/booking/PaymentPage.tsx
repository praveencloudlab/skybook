import { useState } from 'react';
import type { Flight } from '../../api/flights';
import type { Booking } from '../../api/bookings';
import { bookingsApi } from '../../api/bookings';
import { paymentsApi, PAYMENT_METHOD_LABELS, type Payment, type PaymentMethod } from '../../api/payments';
import type { FareType, TravelClass } from '../../api/quotes';
import type { AircraftSeat } from '../../api/seats';
import type { Travellers } from '../../components/TravellersPicker';
import { BookingStepper } from '../../components/BookingStepper';
import { SummaryRail, type SummaryExtra } from '../../components/SummaryRail';
import { Alert, ErrorAlert } from '../../components/Alert';
import { ApiError } from '../../lib/errors';
import { displayCurrency, money } from '../../lib/format';
import { t } from '../../lib/i18n';
import { toPassengerDetail, type PassengerDraft } from './PassengerForm';

const METHOD_BLURB: Partial<Record<PaymentMethod, string>> = {
  CARD: 'Pay securely with cards',
  APPLE_PAY: 'Instant secure pay',
  GOOGLE_PAY: 'Fast track checkout',
  PAYPAL: 'Express checkout',
  UPI: 'Pay by UPI id',
  BANK_TRANSFER: 'Direct from your bank',
};

/**
 * Payment (carrier flow final step): the method tiles, the terms gate and one
 * bold Pay button. This is where the booking is actually created - draft,
 * seat holds, bags and all - then paid: create -> payment row appears (async,
 * Kafka) -> authorize -> capture. Each wait is named on screen rather than
 * hidden behind a spinner.
 *
 * <p>No card fields: payment-service simulates the processor and never takes
 * card data, so asking for a number would be collecting secrets to discard.
 * The tiles choose the METHOD recorded on the payment; the demo note says
 * exactly what will happen.
 */
export function PaymentPage({
  flight,
  returnFlight = null,
  connection = [],
  connectionSeats = [],
  cabin,
  fare,
  currency,
  travellers,
  guests,
  seats,
  returnSeats = [],
  bags,
  returnBags = [],
  contactEmail,
  extras,
  total,
  onBack,
  onBooked,
}: {
  flight: Flight;
  /** Round trip: the return books as segment 1 of the SAME booking - one PNR, one payment. */
  returnFlight?: Flight | null;
  /** Same-carrier through-ticket: the onward connection legs after `flight`. */
  connection?: Flight[];
  /** Seat picks per connection leg (guest order), aligned with `connection`. */
  connectionSeats?: AircraftSeat[][];
  cabin: TravelClass;
  fare: FareType;
  currency: string;
  travellers: Travellers;
  guests: PassengerDraft[];
  seats: AircraftSeat[];
  /** Round trip: each guest's pick on the RETURN leg's seat map. */
  returnSeats?: AircraftSeat[];
  bags: number[];
  /** Round trip: the return direction's own bag counts. */
  returnBags?: number[];
  contactEmail: string;
  extras: SummaryExtra[];
  total: number;
  onBack: () => void;
  onBooked: (booking: Booking, payment: Payment) => void;
}) {
  const [method, setMethod] = useState<PaymentMethod>('CARD');
  const [agreed, setAgreed] = useState(false);
  const [termsOpen, setTermsOpen] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [stage, setStage] = useState<'form' | 'booking' | 'awaitingPayment' | 'paying'>('form');

  async function payNow() {
    if (!agreed || stage !== 'form') {
      return;
    }
    setError(null);
    try {
      setStage('booking');
      // ONE booking whether one-way or round trip: returnFlightId books the
      // return as segment 1 of the same PNR (seat picks apply to the
      // outbound, return seats auto-assign; bags fly both directions).
      const booking = await bookingsApi.create({
        flightId: flight.id,
        ...(returnFlight ? { returnFlightId: returnFlight.id } : {}),
        ...(connection.length ? { connectionFlightIds: connection.map((leg) => leg.id) } : {}),
        passengers: guests.map((g, i) =>
          toPassengerDetail(g, cabin, fare, seats[i]?.seatNumber ?? null, bags[i] ?? 0,
            returnSeats[i]?.seatNumber ?? null,
            connection.map((_, legIdx) => connectionSeats[legIdx]?.[i]?.seatNumber ?? null),
            returnFlight ? (returnBags[i] ?? 0) : 0),
        ),
        contact: {
          contactName: `${guests[0].firstName} ${guests[0].lastName}`.trim(),
          contactEmail: contactEmail.trim(),
        },
      });

      // The payment row is created by payment-service consuming the booking
      // event, so it does not exist yet. Poll rather than assume.
      setStage('awaitingPayment');
      const payment = await waitForPayment(booking.id);

      setStage('paying');
      const authorized = await paymentsApi.authorize(payment.id);
      const captured = await paymentsApi.capture(authorized.id);

      onBooked(booking, captured);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
      setStage('form');
    }
  }

  const busy = stage !== 'form';

  return (
    <>
      <BookingStepper
        current="payment"
        flight={flight}
        route={`${flight.originAirportCode} → ${flight.destinationAirportCode}`}
        onModify={onBack}
      />

      <main className="mx-auto grid max-w-6xl gap-6 px-4 py-6 sm:px-6 lg:grid-cols-[1fr_320px]">
        <div className="rounded-2xl bg-white p-5 shadow-[var(--shadow-card)] sm:p-7">
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">Payment</h1>
          <p className="mt-1 text-sm text-slate-500">How would you like to pay?</p>

          {/* The WHOLE journey being paid for - every leg named, so a
              multi-city or round trip is never mistaken for leg one alone. */}
          {connection.length || returnFlight ? (
            <div className="mt-3 rounded-xl bg-brand-50 px-4 py-3 text-sm text-slate-700 ring-1 ring-inset ring-brand-100">
              <span className="font-bold text-brand-900">Your journey · one booking:</span>{' '}
              {[flight, ...connection].map((leg) => `${leg.originAirportCode} → ${leg.destinationAirportCode} (${leg.flightNumber})`).join(' · ')}
              {returnFlight
                ? ` · return ${returnFlight.originAirportCode} → ${returnFlight.destinationAirportCode} (${returnFlight.flightNumber})`
                : ''}
            </div>
          ) : null}

          <div className="mt-4">
            <ErrorAlert error={error} />
          </div>

          {/* Method tiles. */}
          <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {(Object.keys(PAYMENT_METHOD_LABELS) as PaymentMethod[]).map((value) => {
              const active = method === value;
              return (
                <button
                  key={value}
                  type="button"
                  onClick={() => setMethod(value)}
                  aria-pressed={active}
                  className={
                    'flex items-center justify-between gap-3 rounded-xl border p-3.5 text-left transition ' +
                    (active
                      ? 'border-emerald-500 ring-1 ring-emerald-500'
                      : 'border-slate-200 hover:border-slate-400')
                  }
                >
                  <span className="min-w-0">
                    <span className="block truncate text-sm font-bold text-slate-900">
                      {PAYMENT_METHOD_LABELS[value]}
                    </span>
                    <span className="block truncate text-xs text-slate-500">
                      {METHOD_BLURB[value] ?? ''}
                    </span>
                  </span>
                  {/* Toggle pill. */}
                  <span
                    aria-hidden="true"
                    className={
                      'relative h-5 w-9 shrink-0 rounded-full transition ' +
                      (active ? 'bg-brand-950' : 'bg-slate-200')
                    }
                  >
                    <span
                      className={
                        'absolute top-0.5 h-4 w-4 rounded-full bg-white shadow transition-all ' +
                        (active ? 'left-[18px]' : 'left-0.5')
                      }
                    />
                  </span>
                </button>
              );
            })}
          </div>

          {/* Demo processor note - honest about what happens on Pay. */}
          <div className="mt-4 rounded-xl bg-slate-50 px-4 py-3 text-xs leading-relaxed text-slate-500">
            SkyBook's payment processor is simulated: no card details are needed, and pressing Pay
            authorizes and captures the amount against your chosen method instantly. Your booking and
            invoice are real.
          </div>

          {/* Ticket terms accordion. */}
          <button
            type="button"
            onClick={() => setTermsOpen((o) => !o)}
            aria-expanded={termsOpen}
            className="mt-4 flex w-full items-center justify-between rounded-xl border border-slate-200 px-4 py-3 text-sm font-semibold text-slate-800 transition hover:border-slate-400"
          >
            Ticket terms
            <svg
              viewBox="0 0 24 24"
              className={'h-4 w-4 fill-slate-400 transition-transform ' + (termsOpen ? 'rotate-180' : '')}
              aria-hidden="true"
            >
              <path d="M7.4 8.6 12 13.2l4.6-4.6L18 10l-6 6-6-6z" />
            </svg>
          </button>
          {termsOpen ? (
            <div className="rounded-b-xl border border-t-0 border-slate-200 px-4 py-3 text-xs leading-relaxed text-slate-500">
              Fares are per guest and include taxes. Saver fares refund partially with a cancellation
              fee, Flexi more generously, Premium the most. A cancelled passenger's seat and bags are
              refunded with their fare. Check-in opens 24 hours before departure and closes 45
              minutes before.
            </div>
          ) : null}

          {stage === 'booking' ? <div className="mt-4"><Alert tone="info">Creating your booking…</Alert></div> : null}
          {stage === 'awaitingPayment' ? (
            <div className="mt-4"><Alert tone="info">Setting up your payment — this usually takes a moment.</Alert></div>
          ) : null}
          {stage === 'paying' ? <div className="mt-4"><Alert tone="info">Taking payment…</Alert></div> : null}

          {/* Terms + pay bar. */}
          <div className="mt-6 flex flex-wrap items-center justify-between gap-4 border-t border-slate-100 pt-5">
            <label className="flex cursor-pointer items-start gap-2.5 text-sm text-slate-600">
              <input
                type="checkbox"
                checked={agreed}
                onChange={(e) => setAgreed(e.target.checked)}
                className="mt-0.5 h-4 w-4 rounded border-slate-300 text-brand-600 focus:ring-brand-500/40"
              />
              <span>
                By clicking this box, I agree to all{' '}
                <span className="font-semibold text-brand-700">terms and conditions</span> and{' '}
                <span className="font-semibold text-brand-700">fare rules</span>
              </span>
            </label>
            <div className="flex items-center gap-4">
              <div className="text-right">
                <div className="tabular text-lg font-bold text-slate-900">Total: {money(total, currency)}</div>
                <div className="text-[11px] text-slate-400">(inclusive of all taxes)</div>
                {displayCurrency() !== currency ? (
                  <div className="mt-0.5 max-w-[220px] text-[11px] text-amber-600">
                    {t('payment.chargedIn', { amount: money(total, currency) })}
                  </div>
                ) : null}
              </div>
              <button
                type="button"
                onClick={payNow}
                disabled={!agreed || busy}
                className="rounded-full bg-accent-500 px-8 py-3 text-sm font-bold text-white transition hover:bg-accent-600 disabled:cursor-not-allowed disabled:bg-slate-300"
              >
                {busy ? 'Working…' : t('cta.paynow')}
              </button>
            </div>
          </div>

          <div className="mt-4">
            <button
              type="button"
              onClick={onBack}
              disabled={busy}
              className="rounded-full border border-slate-300 bg-white px-6 py-2.5 text-sm font-semibold text-slate-700 transition hover:bg-slate-50 disabled:opacity-50"
            >
              {t('stepper.back')}
            </button>
          </div>
        </div>

        <SummaryRail
          flight={flight}
          cabin={cabin}
          fare={fare}
          currency={currency}
          travellers={travellers}
          guestNames={guests.map((g) => `${g.title} ${g.firstName} ${g.lastName}`.trim())}
          extras={extras}
          total={total}
        />
      </main>
    </>
  );
}

/**
 * Wait for payment-service to create the payment row. Backs off rather than
 * hammering: the gateway rate-limits at 100 req/min and a tight loop here
 * would trip it, turning a normal wait into a 429.
 */
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
    'Your booking was created, but we could not start the payment. Open it from My bookings to pay.',
  );
}
