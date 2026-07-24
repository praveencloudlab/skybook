import { useState, type FormEvent } from 'react';
import type { Flight } from '../../api/flights';
import type { AircraftSeat } from '../../api/seats';
import {
  FARE_TYPE_LABELS,
  TRAVEL_CLASS_LABELS,
  type FareType,
  type TravelClass,
} from '../../api/quotes';
import { bookingsApi, type Booking } from '../../api/bookings';
import { paymentsApi, PAYMENT_METHOD_LABELS, type Payment, type PaymentMethod } from '../../api/payments';
import { Alert, ErrorAlert } from '../../components/Alert';
import { Button } from '../../components/Button';
import { Field } from '../../components/Field';
import { FlightCard } from '../../components/FlightCard';
import { ApiError, fieldErrors } from '../../lib/errors';
import { money } from '../../lib/format';
import { useSession } from '../auth/useSession';
import {
  emptyPassenger,
  PassengerForm,
  toPassengerDetail,
  validatePassenger,
  type PassengerDraft,
} from './PassengerForm';

/**
 * Passenger details, review and payment (FRONTEND_MODULE.md §5 screens 5-6).
 *
 * <p>This is the journey's first write, and the first place the platform's
 * asynchrony is visible to a passenger. The sequence is:
 *
 * <pre>
 *   create booking            (synchronous - returns a PNR)
 *     → payment row appears   (ASYNC, over Kafka)
 *     → authorise             (synchronous - CAN BE DECLINED)
 *     → capture               (synchronous - issues the invoice)
 *     → booking CONFIRMED     (ASYNC, over Kafka - awaited on the next screen)
 * </pre>
 *
 * <p>The wait for the payment row is the one users would find most baffling if
 * it were unexplained: they have just pressed "Pay" and nothing appears to
 * happen. So it gets its own visible step rather than a spinner.
 */
export function CheckoutPage({
  flight,
  cabin,
  fare,
  baseFare,
  currency,
  seat,
  onBack,
  onBooked,
}: {
  flight: Flight;
  cabin: TravelClass;
  fare: FareType;
  baseFare: number;
  currency: string;
  seat: AircraftSeat | null;
  onBack: () => void;
  onBooked: (booking: Booking, payment: Payment) => void;
}) {
  const { subject } = useSession();

  const [passenger, setPassenger] = useState<PassengerDraft>(emptyPassenger);
  const [contactEmail, setContactEmail] = useState(subject ?? '');
  const [method, setMethod] = useState<PaymentMethod>('CARD');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<ApiError | null>(null);
  const [stage, setStage] = useState<'form' | 'booking' | 'awaitingPayment' | 'paying'>('form');

  const surcharge = seat ? Number(seat.listedSurcharge) || 0 : 0;
  const total = baseFare + surcharge;

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);

    const validation = validatePassenger(passenger);
    if (!contactEmail.trim()) {
      validation.contactEmail = 'A contact email is required';
    }
    setErrors(validation);
    if (Object.keys(validation).length > 0) {
      return;
    }

    try {
      setStage('booking');
      const booking = await bookingsApi.create({
        flightId: flight.id,
        passengers: [toPassengerDetail(passenger, cabin, fare, seat?.seatNumber ?? null)],
        contact: {
          contactName: `${passenger.firstName} ${passenger.lastName}`.trim(),
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
      if (cause instanceof ApiError && cause.kind === 'validation') {
        setErrors(fieldErrors(cause));
      }
    }
  }

  const busy = stage !== 'form';

  return (
    <main className="mx-auto max-w-3xl px-6 py-10">
      <button
        type="button"
        onClick={onBack}
        disabled={busy}
        className="text-sm font-medium text-brand-700 hover:underline disabled:text-slate-400"
      >
        ← Back to seats
      </button>

      <h1 className="mt-4 text-2xl font-semibold tracking-tight text-slate-900">
        Passenger &amp; payment
      </h1>

      <div className="mt-4">
        <FlightCard flight={flight} />
      </div>

      <form onSubmit={handleSubmit} noValidate className="mt-6 space-y-6">
        <ErrorAlert error={error} />

        <section className="space-y-3">
          <h2 className="text-sm font-medium text-slate-700">Passenger</h2>
          <PassengerForm draft={passenger} errors={errors} onChange={setPassenger} />
        </section>

        <section className="space-y-3">
          <h2 className="text-sm font-medium text-slate-700">Contact</h2>
          <Field
            label="Email for the booking confirmation"
            type="email"
            value={contactEmail}
            onChange={(e) => setContactEmail(e.target.value)}
            error={errors.contactEmail}
            autoComplete="email"
          />
        </section>

        <section className="space-y-3">
          <h2 className="text-sm font-medium text-slate-700">Payment</h2>
          <div className="space-y-1.5">
            <label htmlFor="method" className="block text-sm font-medium text-slate-700">
              Method
            </label>
            <select
              id="method"
              value={method}
              onChange={(e) => setMethod(e.target.value as PaymentMethod)}
              className="w-full rounded border border-slate-300 px-3 py-2 text-sm outline-none focus:border-brand-500 focus:ring-2 focus:ring-brand-500/40"
            >
              {(Object.keys(PAYMENT_METHOD_LABELS) as PaymentMethod[]).map((value) => (
                <option key={value} value={value}>
                  {PAYMENT_METHOD_LABELS[value]}
                </option>
              ))}
            </select>
          </div>
        </section>

        {/* The full breakdown before paying - a seat surcharge appearing only on
            the receipt is exactly the sort of surprise that erodes trust. */}
        <dl className="rounded border border-slate-200 bg-white text-sm">
          <div className="flex justify-between px-4 py-2">
            <dt className="text-slate-600">
              {TRAVEL_CLASS_LABELS[cabin]} · {FARE_TYPE_LABELS[fare]}
            </dt>
            <dd className="tabular text-slate-900">{money(baseFare, currency)}</dd>
          </div>
          <div className="flex justify-between border-t border-slate-100 px-4 py-2">
            <dt className="text-slate-600">
              Seat {seat ? seat.seatNumber : '(assigned for you)'}
            </dt>
            <dd className="tabular text-slate-900">
              {surcharge > 0 ? money(surcharge, currency) : 'Free'}
            </dd>
          </div>
          <div className="flex justify-between border-t border-slate-200 px-4 py-2 font-medium">
            <dt className="text-slate-900">Total</dt>
            <dd className="tabular text-slate-900">{money(total, currency)}</dd>
          </div>
        </dl>

        {/* Name the stage. "Setting up your payment" after pressing Pay is
            honest and calm; an unexplained spinner is neither. */}
        {stage === 'booking' ? <Alert tone="info">Creating your booking…</Alert> : null}
        {stage === 'awaitingPayment' ? (
          <Alert tone="info">Setting up your payment — this usually takes a moment.</Alert>
        ) : null}
        {stage === 'paying' ? <Alert tone="info">Taking payment…</Alert> : null}

        <div className="flex justify-end">
          <Button type="submit" busy={busy}>
            Pay {money(total, currency)}
          </Button>
        </div>
      </form>
    </main>
  );
}

/**
 * Wait for payment-service to create the payment row.
 *
 * <p>Backs off rather than hammering: the gateway rate-limits at 100 req/min and
 * a tight loop here would trip it, turning a normal wait into a 429 that looks
 * like a product failure.
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
  // The booking exists; only the payment is missing. Say exactly that, so the
  // passenger knows their booking is not lost.
  throw new ApiError(
    'unavailable',
    0,
    'Your booking was created, but we could not start the payment. Open it from My bookings to pay.',
  );
}
