import { useState, type FormEvent } from 'react';
import { adminApi, type BoardingPassVerification, type Manifest } from '../../api/admin';
import type { CheckIn } from '../../api/checkin';
import type { Flight } from '../../api/flights';
import { ErrorAlert } from '../../components/Alert';
import { AirportField } from '../../components/AirportField';
import { DateField } from '../../components/DateField';
import { ApiError } from '../../lib/errors';
import { addDaysIso, dayAndMonth, time, todayIso } from '../../lib/format';

/**
 * Gate operations - the airport side of the house: pick a flight, see who is
 * checked in, assign the gate, watch the manifest and finalize it after the
 * door closes, and verify a scanned boarding pass. All existing ADMIN
 * endpoints; this screen is the counter they were built for.
 */
export function GateOpsSection() {
  const [origin, setOrigin] = useState('LHR');
  const [destination, setDestination] = useState('DXB');
  const [date, setDate] = useState(addDaysIso(todayIso(), 1));
  const [flights, setFlights] = useState<Flight[] | null>(null);
  const [flight, setFlight] = useState<Flight | null>(null);
  const [checkIns, setCheckIns] = useState<CheckIn[] | null>(null);
  const [manifest, setManifest] = useState<Manifest | null>(null);
  const [gateDraft, setGateDraft] = useState('');
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  async function findFlights(event?: FormEvent) {
    event?.preventDefault();
    setBusy(true);
    setError(null);
    setFlight(null);
    setCheckIns(null);
    setManifest(null);
    try {
      const list = await adminApi.searchFlights({ origin, destination, date });
      list.sort((a, b) => a.departureTime.localeCompare(b.departureTime));
      setFlights(list);
    } catch (e) {
      setError(e instanceof ApiError ? e : null);
    } finally {
      setBusy(false);
    }
  }

  async function openFlight(f: Flight) {
    setFlight(f);
    setError(null);
    setCheckIns(null);
    setManifest(null);
    try {
      const [ins, man] = await Promise.all([
        adminApi.checkInsForFlight(f.id),
        adminApi.manifest(f.id).catch(() => null),
      ]);
      setCheckIns(ins);
      setManifest(man);
      setGateDraft(ins.find((c) => (c as CheckIn & { gate?: string }).gate)?.['gate' as never] ?? '');
    } catch (e) {
      setError(e instanceof ApiError ? e : null);
    }
  }

  async function applyGate() {
    if (!checkIns || !gateDraft.trim()) return;
    setBusy(true);
    setError(null);
    try {
      // The gate is a per-check-in fact server-side; the counter sets it for
      // the whole flight in one go.
      const updated = await Promise.all(checkIns.map((c) => adminApi.assignGate(c.id, gateDraft.trim().toUpperCase())));
      setCheckIns(updated);
    } catch (e) {
      setError(e instanceof ApiError ? e : null);
    } finally {
      setBusy(false);
    }
  }

  async function finalize() {
    if (!flight) return;
    setBusy(true);
    setError(null);
    try {
      setManifest(await adminApi.finalizeManifest(flight.id));
    } catch (e) {
      setError(e instanceof ApiError ? e : null);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-4">
      <form onSubmit={findFlights} className="card grid items-center gap-3 p-4 md:grid-cols-[1fr_1fr_14rem_auto]">
        <AirportField label="From" value={origin} onChange={setOrigin} exclude={destination} />
        <AirportField label="To" value={destination} onChange={setDestination} exclude={origin} />
        <DateField value={date} onChange={setDate} />
        <button type="submit" disabled={busy || origin === destination}
          className="h-[52px] rounded-xl bg-brand-600 px-5 text-sm font-semibold text-white disabled:bg-slate-300">
          {busy && !flight ? 'Loading…' : 'Load flights'}
        </button>
      </form>

      <ErrorAlert error={error} />

      {flights && !flight ? (
        flights.length === 0 ? (
          <p className="card px-4 py-6 text-center text-sm text-slate-500">No flights on this route that day.</p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {flights.map((f) => (
              <button key={f.id} type="button" onClick={() => void openFlight(f)}
                className="card px-4 py-2.5 text-sm transition hover:ring-brand-400">
                <span className="tabular font-bold text-slate-900">{f.flightNumber}</span>
                <span className="tabular ml-2 text-slate-500">{time(f.departureTime)}</span>
                <span className="ml-2 text-xs text-slate-400">T{f.departureTerminal ?? '—'}</span>
              </button>
            ))}
          </div>
        )
      ) : null}

      {flight ? (
        <>
          <div className="card flex flex-wrap items-center justify-between gap-3 p-4">
            <div>
              <span className="tabular text-lg font-bold text-slate-900">{flight.flightNumber}</span>
              <span className="ml-2 text-sm text-slate-500">
                {flight.originAirportCode} → {flight.destinationAirportCode} · {dayAndMonth(flight.departureTime)}{' '}
                {time(flight.departureTime)} · Terminal {flight.departureTerminal ?? '—'}
              </span>
            </div>
            <div className="flex items-center gap-2">
              <input value={gateDraft} onChange={(e) => setGateDraft(e.target.value)} placeholder="Gate e.g. A12"
                className="w-32 rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm outline-none focus:border-brand-500" />
              <button type="button" disabled={busy || !gateDraft.trim() || !checkIns?.length} onClick={() => void applyGate()}
                className="rounded-xl bg-brand-600 px-4 py-2 text-sm font-semibold text-white disabled:bg-slate-300">
                Assign gate
              </button>
              <button type="button" onClick={() => setFlight(null)}
                className="rounded-xl border border-slate-200 px-3 py-2 text-sm text-slate-600 hover:bg-slate-50">
                Back
              </button>
            </div>
          </div>

          {manifest ? (
            <div className="card flex flex-wrap items-center gap-6 p-4">
              <ManifestStat label="Checked in" value={manifest.checkedInCount} />
              <ManifestStat label="Boarded" value={manifest.boardedCount} />
              <ManifestStat label="No-shows" value={manifest.noShowCount} />
              <ManifestStat label="Bags" value={manifest.baggageCount} />
              <span className={'rounded-full px-2.5 py-0.5 text-xs font-bold ' +
                (manifest.finalizedAt ? 'bg-slate-200 text-slate-600' : 'bg-emerald-100 text-emerald-700')}>
                {manifest.finalizedAt ? 'Finalized' : manifest.status}
              </span>
              {!manifest.finalizedAt ? (
                <button type="button" disabled={busy} onClick={() => void finalize()}
                  className="ml-auto rounded-xl border border-red-200 px-4 py-2 text-sm font-semibold text-red-600 hover:bg-red-50 disabled:opacity-50">
                  Finalize manifest
                </button>
              ) : null}
            </div>
          ) : (
            <p className="text-xs text-slate-400">No manifest yet — it appears with the first check-in.</p>
          )}

          {checkIns === null ? (
            <p className="text-sm text-slate-500">Loading check-ins…</p>
          ) : checkIns.length === 0 ? (
            <p className="card px-4 py-6 text-center text-sm text-slate-500">Nobody on this flight has a check-in record yet.</p>
          ) : (
            <div className="card overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-slate-100 text-left text-xs uppercase tracking-wide text-slate-500">
                    <th className="px-4 py-2.5">Passenger</th>
                    <th className="px-4 py-2.5">PNR</th>
                    <th className="px-4 py-2.5">Seat</th>
                    <th className="px-4 py-2.5">Cabin</th>
                    <th className="px-4 py-2.5">Gate</th>
                    <th className="px-4 py-2.5">Status</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-50">
                  {checkIns.map((c) => (
                    <tr key={c.id}>
                      <td className="px-4 py-2.5 font-medium text-slate-900">{c.passengerName}</td>
                      <td className="px-4 py-2.5 font-mono text-slate-600">{c.bookingReference}</td>
                      <td className="tabular px-4 py-2.5 font-mono text-slate-600">{c.seatNumber ?? '—'}</td>
                      <td className="px-4 py-2.5 text-slate-600">{c.travelClass?.toLowerCase()}</td>
                      <td className="tabular px-4 py-2.5 text-slate-600">{(c as CheckIn & { gate?: string }).gate ?? '—'}</td>
                      <td className="px-4 py-2.5">
                        <span className={'rounded-full px-2 py-0.5 text-xs font-bold ' + checkInTone(c.status)}>
                          {c.status.toLowerCase().replace('_', ' ')}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      ) : null}

      <VerifyBox />
    </div>
  );
}

function checkInTone(status: string): string {
  switch (status) {
    case 'CHECKED_IN': return 'bg-emerald-100 text-emerald-700';
    case 'BOARDED': return 'bg-brand-100 text-brand-700';
    case 'NO_SHOW': return 'bg-red-100 text-red-700';
    case 'CANCELLED': return 'bg-slate-200 text-slate-500';
    default: return 'bg-amber-100 text-amber-700';
  }
}

function ManifestStat({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-400">{label}</p>
      <p className="tabular text-xl font-bold text-slate-900">{value}</p>
    </div>
  );
}

/** The scan station: paste the QR token from a pass, get a verdict. */
function VerifyBox() {
  const [token, setToken] = useState('');
  const [result, setResult] = useState<BoardingPassVerification | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  const [rejection, setRejection] = useState<string | null>(null);

  async function verify(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setResult(null);
    setRejection(null);
    try {
      // Success = VALID (the server answers with the pass details);
      // a bad pass comes back as an error carrying the reason.
      setResult(await adminApi.verifyBoardingPass(token.trim()));
    } catch (e) {
      if (e instanceof ApiError) {
        setRejection(e.message || 'rejected');
      } else {
        setError(null);
      }
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="card p-4">
      <h3 className="text-sm font-bold text-slate-900">Verify a boarding pass</h3>
      <p className="mt-0.5 text-xs text-slate-500">
        Paste the token from a scanned QR code — the server checks signature, status and revocation.
      </p>
      <form onSubmit={verify} className="mt-3 flex gap-2">
        <input value={token} onChange={(e) => setToken(e.target.value)} placeholder="Boarding pass token"
          className="flex-1 rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 font-mono text-xs outline-none focus:border-brand-500" />
        <button type="submit" disabled={busy || !token.trim()}
          className="rounded-xl bg-brand-600 px-5 text-sm font-semibold text-white disabled:bg-slate-300">
          Verify
        </button>
      </form>
      <ErrorAlert error={error} />
      {result ? (
        <div className="mt-3 rounded-xl bg-emerald-50 px-4 py-3 text-sm text-emerald-900 ring-1 ring-inset ring-emerald-200">
          <span className="font-bold">VALID — cleared to board</span>
          <div className="tabular mt-1 text-xs">
            {result.passengerName} · {result.bookingReference} · {result.flightNumber} · Seat{' '}
            {result.seatNumber}
            {result.gate ? ` · Gate ${result.gate}` : ''}
            {result.boardingGroup ? ` · Group ${result.boardingGroup}` : ''}
          </div>
        </div>
      ) : null}
      {rejection ? (
        <div className="mt-3 rounded-xl bg-red-50 px-4 py-3 text-sm text-red-900 ring-1 ring-inset ring-red-200">
          <span className="font-bold">NOT VALID</span>
          <span className="ml-2">{rejection}</span>
        </div>
      ) : null}
    </div>
  );
}
