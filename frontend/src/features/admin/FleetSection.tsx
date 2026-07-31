import { useEffect, useState, type FormEvent } from 'react';
import { adminApi, type Aircraft, type AircraftSeatRow } from '../../api/admin';
import { ErrorAlert } from '../../components/Alert';
import { ApiError } from '../../lib/errors';

const AIRCRAFT_STATUSES = ['ACTIVE', 'MAINTENANCE', 'RETIRED'];

/**
 * Fleet & inventory: aircraft with status control and a seat-map inspector,
 * plus per-flight inventory close/reopen (stop-sale and un-stop-sale) -
 * inventory-service's ADMIN surface.
 */
export function FleetSection() {
  const [rows, setRows] = useState<Aircraft[] | null>(null);
  const [seatMapFor, setSeatMapFor] = useState<Aircraft | null>(null);
  const [seats, setSeats] = useState<AircraftSeatRow[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const c = new AbortController();
    adminApi.aircraft(c.signal).then(setRows)
      .catch((e) => !(e as { name?: string })?.name?.includes('Abort') && setError(e instanceof ApiError ? e : null));
    return () => c.abort();
  }, []);

  async function setStatus(a: Aircraft, status: string) {
    setBusy(true);
    setError(null);
    try {
      const updated = await adminApi.setAircraftStatus(a.id, status);
      setRows((r) => (r ?? []).map((x) => (x.id === a.id ? updated : x)));
    } catch (e) {
      setError(e instanceof ApiError ? e : null);
    } finally {
      setBusy(false);
    }
  }

  async function openSeatMap(a: Aircraft) {
    setSeatMapFor(a);
    setSeats(null);
    setError(null);
    try {
      setSeats(await adminApi.seatMap(a.id));
    } catch (e) {
      setError(e instanceof ApiError ? e : null);
    }
  }

  return (
    <div className="space-y-4">
      <ErrorAlert error={error} />

      {rows === null ? (
        <p className="text-sm text-slate-500">Loading fleet…</p>
      ) : (
        <div className="card overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100 text-left text-xs uppercase tracking-wide text-slate-500">
                <th className="px-4 py-2.5">Registration</th>
                <th className="px-4 py-2.5">Aircraft</th>
                <th className="px-4 py-2.5 text-right">Seats</th>
                <th className="px-4 py-2.5">Status</th>
                <th className="px-4 py-2.5 text-right">Seat map</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-50">
              {rows.map((a) => (
                <tr key={a.id}>
                  <td className="px-4 py-2.5 font-mono font-medium text-slate-900">{a.registrationNumber}</td>
                  <td className="px-4 py-2.5 text-slate-600">{a.manufacturer} {a.model}</td>
                  <td className="tabular px-4 py-2.5 text-right text-slate-600">{a.totalSeats}</td>
                  <td className="px-4 py-2.5">
                    <select value={(a.status ?? 'ACTIVE').toUpperCase()} disabled={busy}
                      onChange={(e) => void setStatus(a, e.target.value)}
                      className="rounded-lg border border-slate-200 bg-white px-2 py-1 text-xs outline-none focus:border-brand-500">
                      {AIRCRAFT_STATUSES.map((s) => <option key={s} value={s}>{s.toLowerCase()}</option>)}
                    </select>
                  </td>
                  <td className="px-4 py-2.5 text-right">
                    <button type="button" onClick={() => void openSeatMap(a)}
                      className="rounded-lg px-2 py-1 text-xs font-medium text-brand-700 hover:bg-brand-50">
                      Inspect
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {seatMapFor ? (
        <div className="card p-4">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-bold text-slate-900">
              Seat map — {seatMapFor.registrationNumber} ({seatMapFor.manufacturer} {seatMapFor.model})
            </h3>
            <button type="button" onClick={() => { setSeatMapFor(null); setSeats(null); }}
              className="text-xs font-medium text-slate-500 hover:text-slate-700">Close</button>
          </div>
          {seats === null ? (
            <p className="mt-2 text-sm text-slate-500">Loading…</p>
          ) : (
            <>
              <p className="mt-1 text-xs text-slate-500">
                {seats.length} seats ·{' '}
                {Object.entries(seats.reduce<Record<string, number>>((acc, s) => {
                  acc[s.travelClass] = (acc[s.travelClass] ?? 0) + 1;
                  return acc;
                }, {})).map(([k, v]) => `${v} ${k.toLowerCase().replace('_', ' ')}`).join(' · ')}
              </p>
              <div className="mt-3 flex flex-wrap gap-1">
                {seats.map((s) => (
                  <span key={s.seatNumber} title={`${s.seatNumber} · ${s.travelClass} · ${s.status}`}
                    className={'tabular grid h-7 w-9 place-items-center rounded text-[10px] font-bold ' + seatTone(s)}>
                    {s.seatNumber}
                  </span>
                ))}
              </div>
            </>
          )}
        </div>
      ) : null}

      <InventoryControl />
    </div>
  );
}

function seatTone(s: AircraftSeatRow): string {
  if (s.status !== 'ACTIVE' && s.status !== 'AVAILABLE') return 'bg-slate-200 text-slate-400';
  switch (s.travelClass) {
    case 'FIRST': return 'bg-accent-100 text-accent-700';
    case 'BUSINESS': return 'bg-brand-100 text-brand-700';
    case 'PREMIUM_ECONOMY': return 'bg-emerald-100 text-emerald-700';
    default: return 'bg-slate-100 text-slate-600';
  }
}

/** Stop-sale / un-stop-sale for one flight's inventory. */
function InventoryControl() {
  const [flightId, setFlightId] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  async function run(action: 'close' | 'reopen', event?: FormEvent) {
    event?.preventDefault();
    const id = Number(flightId);
    if (!id) return;
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      const result = action === 'close' ? await adminApi.closeInventory(id) : await adminApi.reopenInventory(id);
      setMessage(`Flight ${id} inventory ${action === 'close' ? 'CLOSED for sale' : 'reopened'}${result.status ? ` (${result.status})` : ''}.`);
    } catch (e) {
      setError(e instanceof ApiError ? e : null);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card p-4">
      <h3 className="text-sm font-bold text-slate-900">Flight inventory stop-sale</h3>
      <p className="mt-0.5 text-xs text-slate-500">
        Close a flight's seat inventory to stop new holds (existing bookings keep their seats), or reopen it.
      </p>
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <input value={flightId} onChange={(e) => setFlightId(e.target.value.replace(/\D/g, ''))} placeholder="Flight id"
          className="tabular w-32 rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm outline-none focus:border-brand-500" />
        <button type="button" disabled={busy || !flightId} onClick={() => void run('close')}
          className="rounded-xl border border-red-200 px-4 py-2 text-sm font-semibold text-red-600 hover:bg-red-50 disabled:opacity-50">
          Close sales
        </button>
        <button type="button" disabled={busy || !flightId} onClick={() => void run('reopen')}
          className="rounded-xl border border-emerald-200 px-4 py-2 text-sm font-semibold text-emerald-700 hover:bg-emerald-50 disabled:opacity-50">
          Reopen
        </button>
      </div>
      <ErrorAlert error={error} />
      {message ? <p className="mt-2 text-sm font-medium text-emerald-700">{message}</p> : null}
    </div>
  );
}
