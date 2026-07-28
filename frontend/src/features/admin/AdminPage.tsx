import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { adminApi, type Aircraft } from '../../api/admin';
import { type Flight, type FlightStatus } from '../../api/flights';
import type { Booking, BookingStatus } from '../../api/bookings';
import { ErrorAlert } from '../../components/Alert';
import { AirportField } from '../../components/AirportField';
import { StatusBadge } from '../bookings/StatusBadge';
import { ApiError } from '../../lib/errors';
import { addDaysIso, dayAndMonth, money, time, todayIso } from '../../lib/format';

/**
 * Admin console (FRONTEND_MODULE.md Module 16).
 *
 * <p>A back-office over the endpoints that actually exist: flight operations,
 * the booking back-office, and the read-only fleet. No fabricated users/reports/
 * logs tabs - those have no backend. Every action is ADMIN-authorised on the
 * server; this UI is the convenient face of it, never the gate.
 */
type Tab = 'overview' | 'flights' | 'bookings' | 'fleet';

const TABS: Array<{ id: Tab; label: string }> = [
  { id: 'overview', label: 'Overview' },
  { id: 'flights', label: 'Flights' },
  { id: 'bookings', label: 'Bookings' },
  { id: 'fleet', label: 'Fleet' },
];

export function AdminPage() {
  const [tab, setTab] = useState<Tab>('overview');

  return (
    <main className="mx-auto max-w-5xl px-6 py-8">
      <div className="flex items-center gap-2">
        <span className="grid h-7 w-7 place-items-center rounded-lg bg-brand-600 text-white">
          <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true">
            <path d="M12 2 4 6v6c0 5 3.4 8.5 8 10 4.6-1.5 8-5 8-10V6l-8-4z" />
          </svg>
        </span>
        <h1 className="display text-3xl text-slate-900">Admin console</h1>
      </div>
      <p className="mt-1 text-sm text-slate-500">Operations back-office — flights, bookings and fleet.</p>

      <div className="mt-5 inline-flex gap-1 rounded-xl bg-slate-100 p-1">
        {TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            onClick={() => setTab(t.id)}
            aria-pressed={tab === t.id}
            className={
              'rounded-lg px-3.5 py-1.5 text-sm font-medium transition ' +
              (tab === t.id ? 'bg-white text-brand-700 shadow-sm' : 'text-slate-500 hover:text-slate-700')
            }
          >
            {t.label}
          </button>
        ))}
      </div>

      <div className="mt-6">
        {tab === 'overview' ? <Overview /> : null}
        {tab === 'flights' ? <Flights /> : null}
        {tab === 'bookings' ? <Bookings /> : null}
        {tab === 'fleet' ? <Fleet /> : null}
      </div>
    </main>
  );
}

/* ---------------- Overview ---------------- */

function Overview() {
  const [bookings, setBookings] = useState<Booking[] | null>(null);
  const [aircraft, setAircraft] = useState<Aircraft[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    const c = new AbortController();
    adminApi.allBookings(c.signal).then(setBookings).catch((e) => !(e?.name === 'AbortError') && setError(e instanceof ApiError ? e : null));
    adminApi.aircraft(c.signal).then(setAircraft).catch(() => {});
    return () => c.abort();
  }, []);

  const counts = useMemo(() => {
    const by: Record<string, number> = {};
    for (const b of bookings ?? []) by[b.bookingStatus] = (by[b.bookingStatus] ?? 0) + 1;
    return by;
  }, [bookings]);

  const revenue = (bookings ?? [])
    .filter((b) => b.bookingStatus === 'CONFIRMED' || b.bookingStatus === 'COMPLETED')
    .reduce((sum, b) => sum + (Number(b.totalFare) || 0), 0);

  return (
    <div className="space-y-4">
      <ErrorAlert error={error} />
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Total bookings" value={String(bookings?.length ?? '—')} />
        <Stat label="Confirmed" value={String(counts.CONFIRMED ?? 0)} tone="emerald" />
        <Stat label="Cancelled" value={String(counts.CANCELLED ?? 0)} />
        <Stat label="Aircraft" value={String(aircraft?.length ?? '—')} />
      </div>
      <div className="card p-5">
        <p className="text-xs font-semibold uppercase tracking-wide text-slate-500">Confirmed revenue</p>
        <p className="tabular mt-1 text-3xl font-semibold text-slate-900">{money(revenue, 'USD')}</p>
        <p className="mt-1 text-xs text-slate-400">Across confirmed and completed bookings.</p>
      </div>
    </div>
  );
}

function Stat({ label, value, tone }: { label: string; value: string; tone?: 'emerald' }) {
  return (
    <div className="card p-4">
      <p className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</p>
      <p className={'tabular mt-1 text-2xl font-semibold ' + (tone === 'emerald' ? 'text-emerald-700' : 'text-slate-900')}>
        {value}
      </p>
    </div>
  );
}

/* ---------------- Flights ---------------- */

const FLIGHT_STATUSES: FlightStatus[] = ['SCHEDULED', 'DELAYED', 'BOARDING', 'DEPARTED', 'ARRIVED', 'CANCELLED'];

function Flights() {
  const [origin, setOrigin] = useState('LHR');
  const [destination, setDestination] = useState('JFK');
  const [date, setDate] = useState(addDaysIso(todayIso(), 1));
  const [rows, setRows] = useState<Flight[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const [actingId, setActingId] = useState<number | null>(null);

  async function run(event?: FormEvent) {
    event?.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const list = await adminApi.searchFlights({ origin, destination, date });
      list.sort((a, b) => a.departureTime.localeCompare(b.departureTime));
      setRows(list);
    } catch (e) {
      setError(e instanceof ApiError ? e : null);
    } finally {
      setBusy(false);
    }
  }

  async function act(id: number, fn: () => Promise<Flight>) {
    setActingId(id);
    setError(null);
    try {
      const updated = await fn();
      setRows((r) => (r ?? []).map((f) => (f.id === id ? updated : f)));
    } catch (e) {
      setError(e instanceof ApiError ? e : null);
    } finally {
      setActingId(null);
    }
  }

  return (
    <div className="space-y-4">
      <form onSubmit={run} className="card grid items-end gap-3 p-4 md:grid-cols-[1fr_1fr_auto_auto]">
        <AirportField label="From" value={origin} onChange={setOrigin} exclude={destination} />
        <AirportField label="To" value={destination} onChange={setDestination} exclude={origin} />
        <label className="text-sm">
          <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate-500">Date</span>
          <input type="date" value={date} onChange={(e) => setDate(e.target.value)} className="tabular w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm outline-none focus:border-brand-500 focus:ring-4 focus:ring-brand-500/15" />
        </label>
        <button type="submit" disabled={busy || origin === destination} className="h-[42px] rounded-xl bg-brand-600 px-5 text-sm font-semibold text-white disabled:bg-slate-300">
          {busy ? 'Searching…' : 'Find flights'}
        </button>
      </form>

      <ErrorAlert error={error} />

      {rows ? (
        rows.length === 0 ? (
          <p className="card px-4 py-6 text-center text-sm text-slate-500">No flights on this route that day.</p>
        ) : (
          <div className="card overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-100 text-left text-xs uppercase tracking-wide text-slate-500">
                  <th className="px-4 py-2.5">Flight</th>
                  <th className="px-4 py-2.5">Departs</th>
                  <th className="px-4 py-2.5">Status</th>
                  <th className="px-4 py-2.5 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-50">
                {rows.map((f) => (
                  <tr key={f.id}>
                    <td className="px-4 py-2.5">
                      <span className="tabular font-medium text-slate-900">{f.flightNumber}</span>
                      <span className="ml-2 text-xs text-slate-400">{f.originAirportCode}→{f.destinationAirportCode}</span>
                    </td>
                    <td className="tabular px-4 py-2.5 text-slate-600">{dayAndMonth(f.departureTime)} {time(f.departureTime)}</td>
                    <td className="px-4 py-2.5">
                      <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600">{f.status.toLowerCase()}</span>
                    </td>
                    <td className="px-4 py-2.5">
                      <div className="flex items-center justify-end gap-2">
                        <select
                          value={f.status}
                          disabled={actingId === f.id}
                          onChange={(e) => act(f.id, () => adminApi.setFlightStatus(f.id, e.target.value as FlightStatus))}
                          className="rounded-lg border border-slate-200 bg-white px-2 py-1 text-xs outline-none focus:border-brand-500"
                        >
                          {FLIGHT_STATUSES.map((s) => (
                            <option key={s} value={s}>{s.toLowerCase()}</option>
                          ))}
                        </select>
                        <button
                          type="button"
                          disabled={actingId === f.id || f.status === 'CANCELLED'}
                          onClick={() => act(f.id, () => adminApi.cancelFlight(f.id))}
                          className="rounded-lg px-2 py-1 text-xs font-medium text-red-600 transition hover:bg-red-50 disabled:text-slate-300"
                        >
                          Cancel
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      ) : (
        <p className="text-sm text-slate-500">Search a route to manage its flights.</p>
      )}
    </div>
  );
}

/* ---------------- Bookings ---------------- */

const BOOKING_STATUSES: BookingStatus[] = ['CREATED', 'CONFIRMED', 'COMPLETED', 'CANCELLED'];

function Bookings() {
  const [rows, setRows] = useState<Booking[] | null>(null);
  const [ref, setRef] = useState('');
  const [status, setStatus] = useState<'' | BookingStatus>('');
  const [error, setError] = useState<ApiError | null>(null);
  const [actingId, setActingId] = useState<number | null>(null);

  async function load(signal?: AbortSignal) {
    setError(null);
    try {
      const list =
        ref || status
          ? await adminApi.searchBookings({ bookingReference: ref || undefined, bookingStatus: status || undefined }, signal)
          : await adminApi.allBookings(signal);
      list.sort((a, b) => b.bookingDate.localeCompare(a.bookingDate));
      setRows(list);
    } catch (e) {
      if ((e as { name?: string })?.name === 'AbortError') return;
      setError(e instanceof ApiError ? e : null);
    }
  }

  useEffect(() => {
    const c = new AbortController();
    void load(c.signal);
    return () => c.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function act(id: number, fn: () => Promise<Booking>) {
    setActingId(id);
    setError(null);
    try {
      const updated = await fn();
      setRows((r) => (r ?? []).map((b) => (b.id === id ? updated : b)));
    } catch (e) {
      setError(e instanceof ApiError ? e : null);
    } finally {
      setActingId(null);
    }
  }

  return (
    <div className="space-y-4">
      <form
        onSubmit={(e) => {
          e.preventDefault();
          void load();
        }}
        className="card grid items-end gap-3 p-4 sm:grid-cols-[1fr_1fr_auto]"
      >
        <label className="text-sm">
          <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate-500">Reference</span>
          <input value={ref} onChange={(e) => setRef(e.target.value.toUpperCase())} placeholder="e.g. SBCUZ6" className="w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm outline-none focus:border-brand-500 focus:ring-4 focus:ring-brand-500/15" />
        </label>
        <label className="text-sm">
          <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate-500">Status</span>
          <select value={status} onChange={(e) => setStatus(e.target.value as '' | BookingStatus)} className="w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm outline-none focus:border-brand-500">
            <option value="">Any</option>
            {BOOKING_STATUSES.map((s) => <option key={s} value={s}>{s.toLowerCase()}</option>)}
          </select>
        </label>
        <button type="submit" className="h-[42px] rounded-xl bg-brand-600 px-5 text-sm font-semibold text-white">Search</button>
      </form>

      <ErrorAlert error={error} />

      {rows === null ? (
        <p className="text-sm text-slate-500">Loading…</p>
      ) : rows.length === 0 ? (
        <p className="card px-4 py-6 text-center text-sm text-slate-500">No bookings match.</p>
      ) : (
        <div className="card overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100 text-left text-xs uppercase tracking-wide text-slate-500">
                <th className="px-4 py-2.5">Reference</th>
                <th className="px-4 py-2.5">Booked</th>
                <th className="px-4 py-2.5">Pax</th>
                <th className="px-4 py-2.5">Status</th>
                <th className="px-4 py-2.5 text-right">Total</th>
                <th className="px-4 py-2.5 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-50">
              {rows.map((b) => (
                <tr key={b.id}>
                  <td className="px-4 py-2.5 font-mono font-medium text-slate-900">{b.bookingReference}</td>
                  <td className="tabular px-4 py-2.5 text-slate-600">{dayAndMonth(b.bookingDate)}</td>
                  <td className="tabular px-4 py-2.5 text-slate-600">{b.passengers.length}</td>
                  <td className="px-4 py-2.5"><StatusBadge status={b.bookingStatus} /></td>
                  <td className="tabular px-4 py-2.5 text-right text-slate-900">{money(b.totalFare, 'USD')}</td>
                  <td className="px-4 py-2.5">
                    <div className="flex items-center justify-end gap-2">
                      {b.bookingStatus === 'CREATED' ? (
                        <button type="button" disabled={actingId === b.id} onClick={() => act(b.id, () => adminApi.confirmBooking(b.id))} className="rounded-lg px-2 py-1 text-xs font-medium text-emerald-700 transition hover:bg-emerald-50 disabled:text-slate-300">Confirm</button>
                      ) : null}
                      {b.bookingStatus === 'CONFIRMED' ? (
                        <button type="button" disabled={actingId === b.id} onClick={() => act(b.id, () => adminApi.completeBooking(b.id))} className="rounded-lg px-2 py-1 text-xs font-medium text-brand-700 transition hover:bg-brand-50 disabled:text-slate-300">Complete</button>
                      ) : null}
                      {b.bookingStatus !== 'CREATED' && b.bookingStatus !== 'CONFIRMED' ? (
                        <span className="text-xs text-slate-300">—</span>
                      ) : null}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

/* ---------------- Fleet ---------------- */

function Fleet() {
  const [rows, setRows] = useState<Aircraft[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    const c = new AbortController();
    adminApi.aircraft(c.signal).then(setRows).catch((e) => !(e?.name === 'AbortError') && setError(e instanceof ApiError ? e : null));
    return () => c.abort();
  }, []);

  return (
    <div className="space-y-4">
      <ErrorAlert error={error} />
      {rows === null ? (
        <p className="text-sm text-slate-500">Loading…</p>
      ) : rows.length === 0 ? (
        <p className="card px-4 py-6 text-center text-sm text-slate-500">No aircraft on file.</p>
      ) : (
        <div className="card overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100 text-left text-xs uppercase tracking-wide text-slate-500">
                <th className="px-4 py-2.5">Registration</th>
                <th className="px-4 py-2.5">Aircraft</th>
                <th className="px-4 py-2.5 text-right">Seats</th>
                <th className="px-4 py-2.5">Status</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-50">
              {rows.map((a) => (
                <tr key={a.id}>
                  <td className="px-4 py-2.5 font-mono font-medium text-slate-900">{a.registrationNumber}</td>
                  <td className="px-4 py-2.5 text-slate-600">{a.manufacturer} {a.model}</td>
                  <td className="tabular px-4 py-2.5 text-right text-slate-600">{a.totalSeats}</td>
                  <td className="px-4 py-2.5">
                    <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600">{(a.status ?? 'active').toLowerCase()}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
