import { useEffect, useState, type FormEvent } from 'react';
import { adminApi, type AdminPayment, type Refund } from '../../api/admin';
import { ErrorAlert } from '../../components/Alert';
import { ApiError } from '../../lib/errors';
import { money } from '../../lib/format';

/**
 * Payments desk: look up a booking's payment and act on it (refund /
 * cancel), with the refund ledger beneath - the money-side mirror of the
 * bookings screen. Every action is the real payment-service ADMIN endpoint;
 * refunding here emits the same events the customer flow does.
 */
export function PaymentsSection() {
  const [bookingRef, setBookingRef] = useState('');
  const [payment, setPayment] = useState<AdminPayment | null>(null);
  const [lookedUp, setLookedUp] = useState(false);
  const [refunds, setRefunds] = useState<Refund[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const c = new AbortController();
    adminApi.refunds(c.signal).then((r) => setRefunds(r.slice().reverse())).catch(() => setRefunds([]));
    return () => c.abort();
  }, []);

  async function lookUp(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setPayment(null);
    setLookedUp(false);
    try {
      // PNR -> booking -> its payment; admins think in PNRs, not payment ids.
      const bookings = await adminApi.searchBookings({ bookingReference: bookingRef.trim().toUpperCase() });
      if (!bookings.length) {
        throw new ApiError('notFound', 404, `No booking ${bookingRef.trim().toUpperCase()}`);
      }
      setPayment(await adminApi.paymentForBooking(bookings[0].id));
      setLookedUp(true);
    } catch (e) {
      setError(e instanceof ApiError ? e : null);
    } finally {
      setBusy(false);
    }
  }

  async function act(fn: (id: number) => Promise<AdminPayment>) {
    if (!payment) return;
    setBusy(true);
    setError(null);
    try {
      setPayment(await fn(payment.id));
      adminApi.refunds().then((r) => setRefunds(r.slice().reverse())).catch(() => {});
    } catch (e) {
      setError(e instanceof ApiError ? e : null);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-4">
      <form onSubmit={lookUp} className="card flex flex-wrap items-end gap-3 p-4">
        <label className="text-sm">
          <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate-500">Booking reference</span>
          <input value={bookingRef} onChange={(e) => setBookingRef(e.target.value.toUpperCase())} placeholder="e.g. SBZXQ3"
            className="w-44 rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 font-mono text-sm outline-none focus:border-brand-500" />
        </label>
        <button type="submit" disabled={busy || !bookingRef.trim()}
          className="h-[42px] rounded-xl bg-brand-600 px-5 text-sm font-semibold text-white disabled:bg-slate-300">
          Find payment
        </button>
      </form>

      <ErrorAlert error={error} />

      {lookedUp && !payment ? (
        <p className="card px-4 py-6 text-center text-sm text-slate-500">That booking has no payment record yet.</p>
      ) : null}

      {payment ? (
        <div className="card flex flex-wrap items-center justify-between gap-4 p-5">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wide text-slate-500">Payment #{payment.id}</p>
            <p className="tabular mt-1 text-2xl font-bold text-slate-900">{money(payment.amount, payment.currency)}</p>
            <p className="tabular mt-0.5 text-xs text-slate-500">
              {payment.paymentReference ?? 'no processor reference'} · {payment.paymentMethod ?? '—'}
            </p>
          </div>
          <div className="flex items-center gap-3">
            <span className={'rounded-full px-3 py-1 text-xs font-bold ' + paymentTone(payment.paymentStatus)}>
              {payment.paymentStatus}
            </span>
            {payment.paymentStatus === 'PAID' ? (
              <button type="button" disabled={busy} onClick={() => void act((id) => adminApi.refundPayment(id))}
                className="rounded-xl border border-red-200 px-4 py-2 text-sm font-semibold text-red-600 hover:bg-red-50 disabled:opacity-50">
                Refund
              </button>
            ) : null}
            {payment.paymentStatus === 'PENDING' ? (
              <button type="button" disabled={busy} onClick={() => void act((id) => adminApi.cancelPayment(id))}
                className="rounded-xl border border-slate-200 px-4 py-2 text-sm font-semibold text-slate-600 hover:bg-slate-50 disabled:opacity-50">
                Cancel payment
              </button>
            ) : null}
          </div>
        </div>
      ) : null}

      <div>
        <h3 className="mb-2 text-sm font-bold text-slate-900">Refund ledger</h3>
        {refunds === null ? (
          <p className="text-sm text-slate-500">Loading…</p>
        ) : refunds.length === 0 ? (
          <p className="card px-4 py-6 text-center text-sm text-slate-500">No refunds issued yet.</p>
        ) : (
          <div className="card overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-100 text-left text-xs uppercase tracking-wide text-slate-500">
                  <th className="px-4 py-2.5">Refund</th>
                  <th className="px-4 py-2.5">Payment</th>
                  <th className="px-4 py-2.5">Reason</th>
                  <th className="px-4 py-2.5 text-right">Amount</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-50">
                {refunds.slice(0, 30).map((r) => (
                  <tr key={r.id}>
                    <td className="tabular px-4 py-2.5 text-slate-900">#{r.id}</td>
                    <td className="tabular px-4 py-2.5 text-slate-600">#{r.paymentId ?? '—'}</td>
                    <td className="max-w-[26rem] truncate px-4 py-2.5 text-slate-600">{r.reason ?? '—'}</td>
                    <td className="tabular px-4 py-2.5 text-right font-semibold text-slate-900">{money(r.amount, r.currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

function paymentTone(status: string): string {
  switch (status) {
    case 'PAID': return 'bg-emerald-100 text-emerald-700';
    case 'REFUNDED': return 'bg-brand-100 text-brand-700';
    case 'FAILED': return 'bg-red-100 text-red-700';
    default: return 'bg-amber-100 text-amber-700';
  }
}
