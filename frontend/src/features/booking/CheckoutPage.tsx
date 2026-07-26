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
import { TripSummaryBar } from '../../components/TripSummaryBar';
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
import { BookForPicker } from './BookForPicker';

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

  // A real booking can carry several passengers - the API models a list. Start
  // with one; the traveller adds more below.
  const [passengers, setPassengers] = useState<PassengerDraft[]>(() => [emptyPassenger()]);
  const [contactEmail, setContactEmail] = useState(subject ?? '');
  const [method, setMethod] = useState<PaymentMethod>('CARD');
  const [agreedTerms, setAgreedTerms] = useState(false);
  const [paxErrors, setPaxErrors] = useState<Record<string, string>[]>([{}]);
  const [formErrors, setFormErrors] = useState<{ contactEmail?: string; terms?: string }>({});
  const [error, setError] = useState<ApiError | null>(null);
  const [stage, setStage] = useState<'form' | 'booking' | 'awaitingPayment' | 'paying'>('form');

  const surcharge = seat ? Number(seat.listedSurcharge) || 0 : 0;
  const paxCount = passengers.length;
  const total = baseFare * paxCount + surcharge;

  function updatePassenger(index: number, draft: PassengerDraft) {
    setPassengers((list) => list.map((p, i) => (i === index ? draft : p)));
  }
  function addPassenger() {
    setPassengers((list) => [...list, emptyPassenger()]);
    setPaxErrors((list) => [...list, {}]);
  }
  function removePassenger(index: number) {
    setPassengers((list) => (list.length > 1 ? list.filter((_, i) => i !== index) : list));
    setPaxErrors((list) => (list.length > 1 ? list.filter((_, i) => i !== index) : list));
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);

    const perPax = passengers.map((p) => validatePassenger(p));
    const fErr: { contactEmail?: string; terms?: string } = {};
    if (!contactEmail.trim()) {
      fErr.contactEmail = 'A contact email is required';
    }
    if (!agreedTerms) {
      fErr.terms = 'Please accept the fare rules and terms to continue';
    }
    setPaxErrors(perPax);
    setFormErrors(fErr);
    if (perPax.some((e) => Object.keys(e).length > 0) || Object.keys(fErr).length > 0) {
      return;
    }

    try {
      setStage('booking');
      const booking = await bookingsApi.create({
        flightId: flight.id,
        // The chosen seat goes to passenger 1; the rest get a free seat assigned
        // at check-in (a single seat was selected upstream).
        passengers: passengers.map((p, i) =>
          toPassengerDetail(p, cabin, fare, i === 0 ? (seat?.seatNumber ?? null) : null),
        ),
        contact: {
          contactName: `${passengers[0].firstName} ${passengers[0].lastName}`.trim(),
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
        const mapped = fieldErrors(cause);
        setPaxErrors((prev) => prev.map((e, i) => (i === 0 ? { ...e, ...mapped } : e)));
      }
    }
  }

  const busy = stage !== 'form';

  return (
    <>
      <TripSummaryBar flight={flight} step="review" onBack={onBack} backLabel="Back to seats" />

      <main className="mx-auto max-w-3xl px-6 py-8">
        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">
          Review &amp; payment
        </h1>
        <p className="mt-1 text-sm text-slate-500">
          {TRAVEL_CLASS_LABELS[cabin]} · {FARE_TYPE_LABELS[fare]}
          {seat ? ` · seat ${seat.seatNumber}` : ' · seat assigned at check-in'}
        </p>

      <form onSubmit={handleSubmit} noValidate className="mt-6 space-y-6">
        <ErrorAlert error={error} />

        <section className="space-y-3">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-medium text-slate-700">
              Passenger{paxCount === 1 ? '' : 's'} ({paxCount})
            </h2>
            <button
              type="button"
              onClick={addPassenger}
              className="text-sm font-semibold text-brand-700 transition hover:underline"
            >
              + Add passenger
            </button>
          </div>

          {passengers.map((p, i) => (
            <div key={i} className="card space-y-3 p-4">
              <div className="flex items-center justify-between">
                <h3 className="text-sm font-semibold text-slate-800">Passenger {i + 1}</h3>
                {i > 0 ? (
                  <button
                    type="button"
                    onClick={() => removePassenger(i)}
                    className="text-xs font-medium text-red-600 transition hover:underline"
                  >
                    Remove
                  </button>
                ) : null}
              </div>
              <BookForPicker
                onSelect={(draft, email) => {
                  updatePassenger(i, draft);
                  if (i === 0 && email) setContactEmail(email);
                }}
              />
              <PassengerForm draft={p} errors={paxErrors[i] ?? {}} onChange={(d) => updatePassenger(i, d)} />
              {i === 0 && seat ? (
                <p className="text-xs text-slate-500">
                  Seat {seat.seatNumber} is assigned to passenger 1. Additional passengers get a free
                  seat at check-in.
                </p>
              ) : null}
            </div>
          ))}
        </section>

        <section className="space-y-3">
          <h2 className="text-sm font-medium text-slate-700">Contact</h2>
          <Field
            label="Email for the booking confirmation"
            type="email"
            value={contactEmail}
            onChange={(e) => setContactEmail(e.target.value)}
            error={formErrors.contactEmail}
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
              className="w-full rounded-xl border border-slate-300 bg-white px-3 py-2 text-sm shadow-sm outline-none transition focus:border-brand-500 focus:ring-2 focus:ring-brand-500/30 outline-none focus:border-brand-500 focus:ring-2 focus:ring-brand-500/40"
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
        <dl className="card text-sm">
          <div className="flex justify-between px-4 py-2">
            <dt className="text-slate-600">
              {TRAVEL_CLASS_LABELS[cabin]} · {FARE_TYPE_LABELS[fare]}
              {paxCount > 1 ? ` · ${money(baseFare, currency)} × ${paxCount}` : ''}
            </dt>
            <dd className="tabular text-slate-900">{money(baseFare * paxCount, currency)}</dd>
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
            <dt className="text-slate-900">
              Total{paxCount > 1 ? ` · ${paxCount} passengers` : ''}
            </dt>
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

        {/* Accept terms (Module 8) - a real airline gates payment on it. */}
        <div>
          <label className="flex cursor-pointer items-start gap-2.5 text-sm text-slate-600">
            <input
              type="checkbox"
              checked={agreedTerms}
              onChange={(e) => {
                setAgreedTerms(e.target.checked);
                if (e.target.checked) setFormErrors((prev) => ({ ...prev, terms: undefined }));
              }}
              className="mt-0.5 h-4 w-4 rounded border-slate-300 text-brand-600 focus:ring-brand-500/40"
            />
            <span>
              I accept the fare rules, baggage policy and{' '}
              <span className="font-medium text-slate-700">terms of carriage</span>, and confirm the
              passenger details are correct.
            </span>
          </label>
          {formErrors.terms ? <p className="mt-1 text-sm text-red-600">{formErrors.terms}</p> : null}
        </div>

        <div className="flex justify-end">
          <Button type="submit" busy={busy} disabled={!agreedTerms}>
            Pay {money(total, currency)}
          </Button>
        </div>
      </form>
      </main>
    </>
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
