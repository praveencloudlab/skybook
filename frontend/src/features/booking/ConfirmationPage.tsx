import { useCallback, useMemo } from 'react';
import { bookingsApi, type Booking } from '../../api/bookings';
import type { Payment } from '../../api/payments';
import { Alert } from '../../components/Alert';
import { Button } from '../../components/Button';
import { money } from '../../lib/format';
import { usePolledResource } from '../../lib/usePolledResource';

/**
 * Confirmation (FRONTEND_MODULE.md §5 screen 7, §3).
 *
 * <p>The payment is captured, but the booking is not CONFIRMED yet: booking
 * -service learns that by consuming the payment event. This screen is where that
 * wait is made honest.
 *
 * <p>The PNR is shown <b>immediately and prominently</b>, before the confirmed
 * status arrives. It exists the moment the booking does, it is what a passenger
 * needs to find the booking again, and withholding it behind a spinner would be
 * the worst possible moment to make someone feel their money went nowhere.
 */
export function ConfirmationPage({
  booking,
  payment,
  onDone,
}: {
  booking: Booking;
  payment: Payment;
  onDone: () => void;
}) {
  const fetchBooking = useCallback(
    (signal: AbortSignal) => bookingsApi.byId(booking.id, signal),
    [booking.id],
  );

  const isConfirmed = useMemo(
    () => (value: Booking) => value.bookingStatus === 'CONFIRMED',
    [],
  );

  const confirmation = usePolledResource<Booking>({
    fetch: fetchBooking,
    isReady: isConfirmed,
    enabled: true,
  });

  const current = confirmation.data ?? booking;

  return (
    <main className="mx-auto max-w-2xl px-6 py-12">
      <div className="rounded border border-slate-200 bg-white p-6">
        <p className="text-sm font-medium text-emerald-700">Payment received</p>

        <h1 className="mt-1 text-2xl font-semibold tracking-tight text-slate-900">
          Your booking reference
        </h1>

        {/* The PNR, large and copyable. It is the one thing worth remembering. */}
        <p className="mt-3 font-mono text-3xl font-semibold tracking-[0.2em] text-brand-700">
          {booking.bookingReference}
        </p>

        <dl className="mt-6 space-y-2 text-sm">
          <div className="flex justify-between">
            <dt className="text-slate-600">Paid</dt>
            <dd className="tabular font-medium text-slate-900">
              {money(payment.capturedAmount ?? payment.amount, payment.currency)}
            </dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-slate-600">Payment reference</dt>
            <dd className="font-mono text-slate-900">{payment.paymentReference}</dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-slate-600">Seat</dt>
            <dd className="text-slate-900">
              {current.passengers[0]?.seatNumber ?? 'Assigned at check-in'}
            </dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-slate-600">Status</dt>
            <dd className="font-medium text-slate-900">{current.bookingStatus}</dd>
          </div>
        </dl>

        <div className="mt-6">
          {confirmation.status === 'working' ? (
            <Alert tone="info">
              Confirming your booking — this usually takes a few seconds.
            </Alert>
          ) : null}

          {confirmation.status === 'ready' ? (
            <Alert tone="info">Confirmed. A confirmation email is on its way.</Alert>
          ) : null}

          {/*
            Timed out is NOT failed, and must not read like it. The payment
            succeeded and the booking exists; only our wait ran out. Saying
            "something went wrong" here would frighten someone whose booking is
            perfectly fine.
          */}
          {confirmation.status === 'timedOut' ? (
            <div className="space-y-2">
              <Alert tone="warning">
                Your payment went through and your booking is saved. It is taking longer than usual
                to confirm — this does not affect your seat.
              </Alert>
              <Button variant="secondary" onClick={confirmation.start}>
                Check again
              </Button>
            </div>
          ) : null}

          {confirmation.status === 'failed' ? (
            <div className="space-y-2">
              <Alert tone="warning">
                We could not check the status just now. Your payment went through and your booking is
                saved under {booking.bookingReference}.
              </Alert>
              <Button variant="secondary" onClick={confirmation.start}>
                Try again
              </Button>
            </div>
          ) : null}
        </div>

        <div className="mt-8 flex justify-end">
          <Button onClick={onDone}>Done</Button>
        </div>
      </div>
    </main>
  );
}
