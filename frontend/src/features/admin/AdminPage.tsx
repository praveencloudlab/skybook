import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react';
import { adminApi, type Aircraft } from '../../api/admin';
import { type Flight, type FlightStatus } from '../../api/flights';
import type { Booking, BookingStatus } from '../../api/bookings';
import { ErrorAlert } from '../../components/Alert';
import { AirportField } from '../../components/AirportField';
import { StatusBadge } from '../bookings/StatusBadge';
import { ApiError } from '../../lib/errors';
import { addDaysIso, dayAndMonth, money, time, todayIso } from '../../lib/format';
import { GateOpsSection } from './GateOpsSection';
import { PaymentsSection } from './PaymentsSection';
import { FleetSection } from './FleetSection';

/**
 * Admin console (FRONTEND_MODULE.md Module 16) - the full back-office over
 * every ADMIN-authorised endpoint: flight lifecycle, the bookings desk,
 * payments & refunds, gate operations and fleet/inventory. Sidebar shell:
 * ops tools read as a workspace, not a website. The server authorises every
 * action; this UI is the convenient face, never the gate.
 */
type Section = 'overview' | 'flights' | 'bookings' | 'payments' | 'gateops' | 'fleet';

const SECTIONS: Array<{ id: Section; label: string; hint: string; icon: ReactNode }> = [
  { id: 'overview', label: 'Overview', hint: 'Today at a glance', icon: <IconGrid /> },
  { id: 'flights', label: 'Flights', hint: 'Schedule & lifecycle', icon: <IconPlane /> },
  { id: 'bookings', label: 'Bookings', hint: 'The reservations desk', icon: <IconTicket /> },
  { id: 'payments', label: 'Payments', hint: 'Money & refunds', icon: <IconCard /> },
  { id: 'gateops', label: 'Gate ops', hint: 'Check-in, gates, manifests', icon: <IconGate /> },
  { id: 'fleet', label: 'Fleet', hint: 'Aircraft & inventory', icon: <IconWrench /> },
];

export function AdminPage() {
  const [section, setSection] = useState<Section>('overview');
  const active = SECTIONS.find((s) => s.id === section)!;

  return (
    <main className="mx-auto flex max-w-7xl gap-0 px-0 sm:px-4 lg:px-6">
      {/* Sidebar */}
      <aside className="hidden w-56 shrink-0 py-8 pr-4 sm:block">
        <div className="rounded-2xl bg-brand-950 p-3 text-white shadow-[var(--shadow-card)]">
          <div className="flex items-center gap-2 px-2 pb-3 pt-1">
            <span className="grid h-8 w-8 place-items-center rounded-xl bg-accent-500">
              <svg viewBox="0 0 24 24" className="h-4 w-4 fill-white" aria-hidden="true">
                <path d="M12 2 4 6v6c0 5 3.4 8.5 8 10 4.6-1.5 8-5 8-10V6l-8-4z" />
              </svg>
            </span>
            <div>
              <p className="text-sm font-bold leading-none">Operations</p>
              <p className="mt-0.5 text-[10px] uppercase tracking-widest text-white/50">Back-office</p>
            </div>
          </div>
          <nav className="space-y-1">
            {SECTIONS.map((s) => (
              <button key={s.id} type="button" onClick={() => setSection(s.id)} aria-pressed={section === s.id}
                className={'flex w-full items-center gap-2.5 rounded-xl px-3 py-2 text-left text-sm transition ' +
                  (section === s.id ? 'bg-white/10 font-semibold text-white ring-1 ring-inset ring-accent-500/60' : 'text-white/70 hover:bg-white/5 hover:text-white')}>
                <span className={section === s.id ? 'text-accent-400' : 'text-white/40'}>{s.icon}</span>
                {s.label}
              </button>
            ))}
          </nav>
        </div>
      </aside>

      {/* Content */}
      <div className="min-w-0 flex-1 px-4 py-8 sm:px-0">
        {/* Mobile section picker */}
        <div className="mb-4 flex gap-1 overflow-x-auto rounded-xl bg-slate-100 p-1 sm:hidden">
          {SECTIONS.map((s) => (
            <button key={s.id} type="button" onClick={() => setSection(s.id)}
              className={'shrink-0 rounded-lg px-3 py-1.5 text-xs font-medium ' +
                (section === s.id ? 'bg-white text-brand-700 shadow-sm' : 'text-slate-500')}>
              {s.label}
            </button>
          ))}
        </div>

        <div className="mb-5">
          <h1 className="display text-3xl text-slate-900">{active.label}</h1>
          <p className="mt-0.5 text-sm text-slate-500">{active.hint}</p>
        </div>

        {section === 'overview' ? <Overview onGo={setSection} /> : null}
        {section === 'flights' ? <Flights /> : null}
        {section === 'bookings' ? <Bookings /> : null}
        {section === 'payments' ? <PaymentsSection /> : null}
        {section === 'gateops' ? <GateOpsSection /> : null}
        {section === 'fleet' ? <FleetSection /> : null}
      </div>
    </main>
  );
}

/* ---------------- Overview ---------------- */

function Overview({ onGo }: { onGo: (s: Section) => void }) {
  const [bookings, setBookings] = useState<Booking[] | null>(null);
  const [aircraft, setAircraft] = useState<Aircraft[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    const c = new AbortController();
    adminApi.allBookings(c.signal).then(setBookings)
      .catch((e) => !(e as { name?: string })?.name?.includes('Abort') && setError(e instanceof ApiError ? e : null));
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
  const today = todayIso();
  const bookedToday = (bookings ?? []).filter((b) => b.bookingDate?.startsWith(today)).length;

  const statusOrder: BookingStatus[] = ['CONFIRMED', 'CREATED', 'PARTIALLY_CANCELLED' as BookingStatus, 'COMPLETED', 'CANCELLED'];
  const total = bookings?.length ?? 0;

  return (
    <div className="space-y-4">
      <ErrorAlert error={error} />
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Kpi label="Confirmed revenue" value={money(revenue, 'GBP')} sub="confirmed + completed" accent />
        <Kpi label="Bookings" value={String(total || '—')} sub={`${bookedToday} made today`} />
        <Kpi label="Active PNRs" value={String((counts.CONFIRMED ?? 0) + (counts.CREATED ?? 0))} sub="confirmed or awaiting payment" />
        <Kpi label="Fleet" value={String(aircraft?.length ?? '—')} sub="aircraft on file" />
      </div>

      {/* Bookings by status - proportional bars, not a fake chart. */}
      <div className="card p-5">
        <p className="text-xs font-semibold uppercase tracking-wide text-slate-500">Bookings by status</p>
        <div className="mt-3 space-y-2">
          {statusOrder.filter((s) => counts[s]).map((s) => (
            <div key={s} className="flex items-center gap-3">
              <span className="w-40 shrink-0 text-xs font-medium text-slate-600">{s.toLowerCase().replace('_', ' ')}</span>
              <div className="h-2.5 flex-1 overflow-hidden rounded-full bg-slate-100">
                <div className={'h-full rounded-full ' + barTone(s)} style={{ width: `${Math.max(2, (counts[s] / Math.max(1, total)) * 100)}%` }} />
              </div>
              <span className="tabular w-10 text-right text-xs font-bold text-slate-900">{counts[s]}</span>
            </div>
          ))}
        </div>
      </div>

      <div className="grid gap-4 sm:grid-cols-3">
        <QuickAction title="Run the gate" text="Check-ins, gate assignment, manifests and pass verification." onClick={() => onGo('gateops')} />
        <QuickAction title="Work the desk" text="Find a booking, confirm, complete, cancel or refund it." onClick={() => onGo('bookings')} />
        <QuickAction title="Add a flight" text="Create a departure — terminals assign themselves." onClick={() => onGo('flights')} />
      </div>
    </div>
  );
}

function barTone(s: string): string {
  switch (s) {
    case 'CONFIRMED': return 'bg-emerald-500';
    case 'CREATED': return 'bg-amber-400';
    case 'COMPLETED': return 'bg-brand-500';
    case 'PARTIALLY_CANCELLED': return 'bg-orange-400';
    default: return 'bg-slate-300';
  }
}

function Kpi({ label, value, sub, accent }: { label: string; value: string; sub?: string; accent?: boolean }) {
  return (
    <div className={'card p-4 ' + (accent ? 'bg-brand-950 text-white ring-0' : '')}>
      <p className={'text-xs font-medium uppercase tracking-wide ' + (accent ? 'text-white/60' : 'text-slate-500')}>{label}</p>
      <p className={'tabular mt-1 text-2xl font-bold ' + (accent ? 'text-accent-300' : 'text-slate-900')}>{value}</p>
      {sub ? <p className={'mt-0.5 text-[11px] ' + (accent ? 'text-white/50' : 'text-slate-400')}>{sub}</p> : null}
    </div>
  );
}

function QuickAction({ title, text, onClick }: { title: string; text: string; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick}
      className="card p-4 text-left transition hover:ring-brand-400">
      <p className="text-sm font-bold text-slate-900">{title} →</p>
      <p className="mt-1 text-xs text-slate-500">{text}</p>
    </button>
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
  const [reschedule, setReschedule] = useState<Flight | null>(null);
  const [creating, setCreating] = useState(false);

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

  async function act(id: number, fn: () => Promise<Flight | void>) {
    setActingId(id);
    setError(null);
    try {
      const updated = await fn();
      setRows((r) => updated ? (r ?? []).map((f) => (f.id === id ? updated : f)) : (r ?? []).filter((f) => f.id !== id));
    } catch (e) {
      setError(e instanceof ApiError ? e : null);
    } finally {
      setActingId(null);
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <form onSubmit={run} className="card grid flex-1 items-end gap-3 p-4 md:grid-cols-[1fr_1fr_auto_auto]">
          <AirportField label="From" value={origin} onChange={setOrigin} exclude={destination} />
          <AirportField label="To" value={destination} onChange={setDestination} exclude={origin} />
          <label className="text-sm">
            <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate-500">Date</span>
            <input type="date" value={date} onChange={(e) => setDate(e.target.value)}
              className="tabular w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm outline-none focus:border-brand-500 focus:ring-4 focus:ring-brand-500/15" />
          </label>
          <button type="submit" disabled={busy || origin === destination}
            className="h-[42px] rounded-xl bg-brand-600 px-5 text-sm font-semibold text-white disabled:bg-slate-300">
            {busy ? 'Searching…' : 'Find flights'}
          </button>
        </form>
        <button type="button" onClick={() => setCreating((v) => !v)}
          className="h-[42px] rounded-xl border border-accent-500 px-5 text-sm font-bold text-accent-600 transition hover:bg-accent-500 hover:text-white">
          {creating ? 'Close' : '+ New flight'}
        </button>
      </div>

      {creating ? <CreateFlightForm onCreated={() => void run()} /> : null}

      <ErrorAlert error={error} />

      {reschedule ? (
        <RescheduleForm flight={reschedule} onClose={() => setReschedule(null)}
          onSaved={(updated) => {
            setRows((r) => (r ?? []).map((f) => (f.id === updated.id ? updated : f)));
            setReschedule(null);
          }} />
      ) : null}

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
                  <th className="px-4 py-2.5">Terminals</th>
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
                      <span className="ml-2 text-[10px] text-slate-300">#{f.id}</span>
                    </td>
                    <td className="tabular px-4 py-2.5 text-slate-600">{dayAndMonth(f.departureTime)} {time(f.departureTime)}</td>
                    <td className="tabular px-4 py-2.5 text-xs text-slate-500">
                      T{f.departureTerminal ?? '—'} → T{f.arrivalTerminal ?? '—'}
                    </td>
                    <td className="px-4 py-2.5">
                      <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600">{f.status.toLowerCase()}</span>
                    </td>
                    <td className="px-4 py-2.5">
                      <div className="flex items-center justify-end gap-1.5">
                        <select value={f.status} disabled={actingId === f.id}
                          onChange={(e) => act(f.id, () => adminApi.setFlightStatus(f.id, e.target.value as FlightStatus))}
                          className="rounded-lg border border-slate-200 bg-white px-2 py-1 text-xs outline-none focus:border-brand-500">
                          {FLIGHT_STATUSES.map((s) => <option key={s} value={s}>{s.toLowerCase()}</option>)}
                        </select>
                        <button type="button" disabled={actingId === f.id} onClick={() => setReschedule(f)}
                          className="rounded-lg px-2 py-1 text-xs font-medium text-brand-700 hover:bg-brand-50 disabled:text-slate-300">
                          Reschedule
                        </button>
                        <button type="button" disabled={actingId === f.id || f.status === 'CANCELLED'}
                          onClick={() => act(f.id, () => adminApi.cancelFlight(f.id))}
                          className="rounded-lg px-2 py-1 text-xs font-medium text-red-600 hover:bg-red-50 disabled:text-slate-300">
                          Cancel
                        </button>
                        <button type="button" disabled={actingId === f.id}
                          onClick={() => act(f.id, () => adminApi.deleteFlight(f.id))}
                          title="Delete permanently (fails if the flight has bookings)"
                          className="rounded-lg px-2 py-1 text-xs font-medium text-slate-400 hover:bg-slate-100 hover:text-red-600 disabled:text-slate-300">
                          Delete
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
        <p className="text-sm text-slate-500">Search a route to manage its flights, or create a new one.</p>
      )}
    </div>
  );
}

function CreateFlightForm({ onCreated }: { onCreated: () => void }) {
  const [flightNumber, setFlightNumber] = useState('SB');
  const [airlineCode, setAirlineCode] = useState('SB');
  const [origin, setOrigin] = useState('LHR');
  const [destination, setDestination] = useState('DXB');
  const [departure, setDeparture] = useState(`${addDaysIso(todayIso(), 2)}T09:00`);
  const [arrival, setArrival] = useState(`${addDaysIso(todayIso(), 2)}T16:00`);
  const [fleet, setFleet] = useState<Aircraft[]>([]);
  const [aircraftId, setAircraftId] = useState<number | ''>('');
  const [error, setError] = useState<ApiError | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const c = new AbortController();
    adminApi.aircraft(c.signal).then((a) => {
      setFleet(a);
      if (a.length) setAircraftId(a[0].id);
    }).catch(() => {});
    return () => c.abort();
  }, []);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setDone(null);
    try {
      const created = await adminApi.createFlight({
        flightNumber, airlineCode,
        originAirportCode: origin, destinationAirportCode: destination,
        departureTime: `${departure}:00`, arrivalTime: `${arrival}:00`,
      });
      // A flight without inventory sells seats unpriced - so the console
      // creates the cabin in the same breath, on the chosen aircraft.
      let inventoryNote = 'no inventory (unpriced seats)';
      if (aircraftId !== '') {
        await adminApi.createInventory(created.id, aircraftId);
        const chosen = fleet.find((a) => a.id === aircraftId);
        inventoryNote = `${chosen?.registrationNumber} ${chosen?.model} cabin (${chosen?.totalSeats} seats)`;
      }
      setDone(`Created ${created.flightNumber} (#${created.id}) - T${created.departureTerminal} → T${created.arrivalTerminal} · ${inventoryNote}.`);
      onCreated();
    } catch (e) {
      setError(e instanceof ApiError ? e : null);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="card space-y-3 border-l-4 border-l-accent-500 p-4">
      <p className="text-sm font-bold text-slate-900">Create a flight</p>
      <div className="grid gap-3 md:grid-cols-3">
        <label className="text-sm">
          <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate-500">Flight number</span>
          <input value={flightNumber} onChange={(e) => { const v = e.target.value.toUpperCase(); setFlightNumber(v); setAirlineCode(v.slice(0, 2)); }}
            className="w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 font-mono text-sm outline-none focus:border-brand-500" />
        </label>
        <AirportField label="From" value={origin} onChange={setOrigin} exclude={destination} />
        <AirportField label="To" value={destination} onChange={setDestination} exclude={origin} />
        <label className="text-sm">
          <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate-500">Departs</span>
          <input type="datetime-local" value={departure} onChange={(e) => setDeparture(e.target.value)}
            className="tabular w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm outline-none focus:border-brand-500" />
        </label>
        <label className="text-sm">
          <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate-500">Arrives</span>
          <input type="datetime-local" value={arrival} onChange={(e) => setArrival(e.target.value)}
            className="tabular w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm outline-none focus:border-brand-500" />
        </label>
        <label className="text-sm">
          <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate-500">Aircraft (cabin)</span>
          <select value={aircraftId} onChange={(e) => setAircraftId(e.target.value ? Number(e.target.value) : '')}
            className="w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm outline-none focus:border-brand-500">
            {fleet.map((a) => (
              <option key={a.id} value={a.id}>{a.registrationNumber} · {a.model} ({a.totalSeats})</option>
            ))}
            <option value="">No inventory (unpriced seats)</option>
          </select>
        </label>
        <div className="flex items-end">
          <button type="submit" disabled={busy || origin === destination}
            className="h-[42px] w-full rounded-xl bg-accent-500 px-5 text-sm font-bold text-white hover:bg-accent-600 disabled:bg-slate-300">
            {busy ? 'Creating…' : 'Create flight'}
          </button>
        </div>
      </div>
      <p className="text-[11px] text-slate-400">
        Terminals assign automatically from the carrier's real airport assignments; the seat inventory is created on
        the chosen aircraft's cabin in the same step.
      </p>
      <ErrorAlert error={error} />
      {done ? <p className="text-sm font-medium text-emerald-700">{done}</p> : null}
    </form>
  );
}

function RescheduleForm({ flight, onClose, onSaved }: { flight: Flight; onClose: () => void; onSaved: (f: Flight) => void }) {
  const [departure, setDeparture] = useState(flight.departureTime.slice(0, 16));
  const [arrival, setArrival] = useState(flight.arrivalTime.slice(0, 16));
  const [remarks, setRemarks] = useState('');
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onSaved(await adminApi.rescheduleFlight(flight.id, `${departure}:00`.slice(0, 19), `${arrival}:00`.slice(0, 19), remarks || undefined));
    } catch (e) {
      setError(e instanceof ApiError ? e : null);
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="card space-y-3 border-l-4 border-l-brand-500 p-4">
      <div className="flex items-center justify-between">
        <p className="text-sm font-bold text-slate-900">Reschedule {flight.flightNumber} <span className="text-xs font-normal text-slate-400">#{flight.id}</span></p>
        <button type="button" onClick={onClose} className="text-xs font-medium text-slate-500 hover:text-slate-700">Close</button>
      </div>
      <div className="grid gap-3 md:grid-cols-3">
        <label className="text-sm">
          <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate-500">New departure</span>
          <input type="datetime-local" value={departure} onChange={(e) => setDeparture(e.target.value)}
            className="tabular w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm outline-none focus:border-brand-500" />
        </label>
        <label className="text-sm">
          <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate-500">New arrival</span>
          <input type="datetime-local" value={arrival} onChange={(e) => setArrival(e.target.value)}
            className="tabular w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm outline-none focus:border-brand-500" />
        </label>
        <label className="text-sm">
          <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate-500">Remarks</span>
          <input value={remarks} onChange={(e) => setRemarks(e.target.value)} placeholder="Optional"
            className="w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm outline-none focus:border-brand-500" />
        </label>
      </div>
      <button type="submit" disabled={busy}
        className="rounded-xl bg-brand-600 px-5 py-2 text-sm font-semibold text-white disabled:bg-slate-300">
        {busy ? 'Saving…' : 'Save schedule'}
      </button>
      <ErrorAlert error={error} />
    </form>
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
  const [cancelId, setCancelId] = useState<number | null>(null);
  const [cancelReason, setCancelReason] = useState('');

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
      <form onSubmit={(e) => { e.preventDefault(); void load(); }}
        className="card grid items-end gap-3 p-4 sm:grid-cols-[1fr_1fr_auto]">
        <label className="text-sm">
          <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate-500">Reference</span>
          <input value={ref} onChange={(e) => setRef(e.target.value.toUpperCase())} placeholder="e.g. SBCUZ6"
            className="w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm outline-none focus:border-brand-500 focus:ring-4 focus:ring-brand-500/15" />
        </label>
        <label className="text-sm">
          <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate-500">Status</span>
          <select value={status} onChange={(e) => setStatus(e.target.value as '' | BookingStatus)}
            className="w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm outline-none focus:border-brand-500">
            <option value="">Any</option>
            {BOOKING_STATUSES.map((s) => <option key={s} value={s}>{s.toLowerCase()}</option>)}
          </select>
        </label>
        <button type="submit" className="h-[42px] rounded-xl bg-brand-600 px-5 text-sm font-semibold text-white">Search</button>
      </form>

      <ErrorAlert error={error} />

      {cancelId !== null ? (
        <form onSubmit={(e) => {
            e.preventDefault();
            void act(cancelId, () => adminApi.cancelBooking(cancelId, cancelReason || 'Cancelled by back-office'));
            setCancelId(null);
            setCancelReason('');
          }}
          className="card flex flex-wrap items-end gap-3 border-l-4 border-l-red-400 p-4">
          <label className="flex-1 text-sm">
            <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate-500">
              Cancellation reason (booking #{cancelId})
            </span>
            <input value={cancelReason} onChange={(e) => setCancelReason(e.target.value)} placeholder="e.g. Customer request via phone" autoFocus
              className="w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm outline-none focus:border-brand-500" />
          </label>
          <button type="submit" className="h-[42px] rounded-xl bg-red-600 px-5 text-sm font-semibold text-white">Cancel booking</button>
          <button type="button" onClick={() => setCancelId(null)}
            className="h-[42px] rounded-xl border border-slate-200 px-4 text-sm text-slate-600">Keep it</button>
        </form>
      ) : null}

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
                <th className="px-4 py-2.5">Journey</th>
                <th className="px-4 py-2.5">Pax</th>
                <th className="px-4 py-2.5">Status</th>
                <th className="px-4 py-2.5 text-right">Total</th>
                <th className="px-4 py-2.5 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-50">
              {rows.slice(0, 50).map((b) => (
                <tr key={b.id}>
                  <td className="px-4 py-2.5 font-mono font-medium text-slate-900">{b.bookingReference}</td>
                  <td className="tabular px-4 py-2.5 text-slate-600">{dayAndMonth(b.bookingDate)}</td>
                  <td className="tabular px-4 py-2.5 text-xs text-slate-500">
                    {(b.segments?.length ?? 1) > 1 ? `${b.segments!.length} segments` : 'one-way'}
                  </td>
                  <td className="tabular px-4 py-2.5 text-slate-600">
                    {new Set(b.passengers.map((p) => p.passengerId ?? `${p.firstName} ${p.lastName}`)).size}
                  </td>
                  <td className="px-4 py-2.5"><StatusBadge status={b.bookingStatus} /></td>
                  <td className="tabular px-4 py-2.5 text-right text-slate-900">{money(b.totalFare, 'GBP')}</td>
                  <td className="px-4 py-2.5">
                    <div className="flex items-center justify-end gap-1.5">
                      {b.bookingStatus === 'CREATED' ? (
                        <button type="button" disabled={actingId === b.id} onClick={() => act(b.id, () => adminApi.confirmBooking(b.id))}
                          className="rounded-lg px-2 py-1 text-xs font-medium text-emerald-700 hover:bg-emerald-50 disabled:text-slate-300">Confirm</button>
                      ) : null}
                      {b.bookingStatus === 'CONFIRMED' ? (
                        <button type="button" disabled={actingId === b.id} onClick={() => act(b.id, () => adminApi.completeBooking(b.id))}
                          className="rounded-lg px-2 py-1 text-xs font-medium text-brand-700 hover:bg-brand-50 disabled:text-slate-300">Complete</button>
                      ) : null}
                      {b.bookingStatus === 'CREATED' || b.bookingStatus === 'CONFIRMED' || b.bookingStatus === 'PARTIALLY_CANCELLED' ? (
                        <button type="button" disabled={actingId === b.id} onClick={() => setCancelId(b.id)}
                          className="rounded-lg px-2 py-1 text-xs font-medium text-red-600 hover:bg-red-50 disabled:text-slate-300">Cancel</button>
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

/* ---------------- Icons (inline, 16px) ---------------- */

function IconGrid() { return <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true"><path d="M3 3h8v8H3zM13 3h8v8h-8zM3 13h8v8H3zM13 13h8v8h-8z"/></svg>; }
function IconPlane() { return <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true"><path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z"/></svg>; }
function IconTicket() { return <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true"><path d="M4 5a2 2 0 0 0-2 2v3a2 2 0 1 1 0 4v3a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-3a2 2 0 1 1 0-4V7a2 2 0 0 0-2-2H4zm9 2h2v2h-2V7zm0 4h2v2h-2v-2zm0 4h2v2h-2v-2z"/></svg>; }
function IconCard() { return <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true"><path d="M2 6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v2H2V6zm0 4h20v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-8zm3 5h6v2H5v-2z"/></svg>; }
function IconGate() { return <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true"><path d="M12 2a5 5 0 0 1 5 5c0 3.87-5 11-5 11S7 10.87 7 7a5 5 0 0 1 5-5zm0 7a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM4 20h16v2H4z"/></svg>; }
function IconWrench() { return <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true"><path d="M22 6.5a5.5 5.5 0 0 1-7.6 5.06L6.6 19.36a2 2 0 1 1-2.83-2.83l7.8-7.79A5.5 5.5 0 0 1 18.5 1l-3 3L17 5.5l1.5 1.5 3-3c.32.77.5 1.6.5 2.5z"/></svg>; }
