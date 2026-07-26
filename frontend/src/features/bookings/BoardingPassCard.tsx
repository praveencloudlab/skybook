import type { BoardingPass, CheckIn } from '../../api/checkin';
import { AIRPORTS } from '../../api/flights';
import { dayAndMonth, time } from '../../lib/format';
import { printBoardingPass } from './printable';

/**
 * The boarding pass (FRONTEND_MODULE.md §5 screen 10), laid out like a real
 * airline ticket: a FROM -> ✈ -> TO hero with city names and times, the full
 * detail row, and a perforated tear-off stub carrying the barcode - the shape a
 * passenger recognises at a glance.
 *
 * <p>Route/date/class/PNR come from the check-in record (the pass endpoint does
 * not repeat them); the arrival time comes from the flight when the host has it.
 */
export function BoardingPassCard({
  pass,
  record,
  arrivalTime,
}: {
  pass: BoardingPass;
  record?: CheckIn;
  arrivalTime?: string;
}) {
  const from = record?.originAirportCode ?? '—';
  const to = record?.destinationAirportCode ?? '—';
  const cabin = record ? cabinLabel(record.travelClass) : '';
  const boarding = pass.boardingTime
    ? new Date(pass.boardingTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : '—';

  return (
    <div className="overflow-x-auto">
      <div className="flex min-w-[620px] overflow-hidden rounded-xl shadow-[var(--shadow-lift)] ring-1 ring-brand-900/10">
        {/* Main coupon */}
        <div className="relative flex-1 bg-gradient-to-br from-brand-900 to-brand-950 p-5 text-white">
          <div className="grid-texture absolute inset-0 opacity-40" />
          <div className="relative">
            <div className="flex items-center justify-between">
              <span className="flex items-center gap-2 text-xs font-bold uppercase tracking-[0.2em] text-white/80">
                <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true">
                  <path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z" />
                </svg>
                Boarding pass
              </span>
              <button
                type="button"
                onClick={() => printBoardingPass(pass, record, arrivalTime)}
                className="inline-flex items-center gap-1 rounded-md bg-white/15 px-2 py-1 text-[11px] font-semibold text-white transition hover:bg-white/25"
                title="Download / print boarding pass"
              >
                <svg viewBox="0 0 24 24" className="h-3 w-3 fill-current" aria-hidden="true">
                  <path d="M5 20h14v-2H5v2zM12 2v10.17l3.59-3.58L17 10l-5 5-5-5 1.41-1.41L12 12.17V2z" />
                </svg>
                Download
              </button>
            </div>

            {/* FROM -> plane -> TO */}
            <div className="mt-4 flex items-end gap-3">
              <div>
                <div className="text-[10px] font-medium uppercase tracking-wider text-white/50">From</div>
                <div className="text-4xl font-bold leading-none tracking-tight">{from}</div>
                <div className="mt-1 text-xs font-medium text-white/70">{cityFor(from)}</div>
                {record ? (
                  <div className="tabular mt-0.5 text-xs text-white/50">
                    {dayAndMonth(record.departureTime)} · {time(record.departureTime)}
                  </div>
                ) : null}
              </div>

              <div className="mb-6 flex flex-1 items-center gap-1 text-white/40">
                <span className="h-1.5 w-1.5 rounded-full bg-white/50" />
                <span className="flex-1 border-t border-dashed border-white/30" />
                <svg viewBox="0 0 24 24" className="h-5 w-5 fill-white/80" aria-hidden="true">
                  <path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z" transform="rotate(90 12 12)" />
                </svg>
                <span className="flex-1 border-t border-dashed border-white/30" />
              </div>

              <div className="text-right">
                <div className="text-[10px] font-medium uppercase tracking-wider text-white/50">To</div>
                <div className="text-4xl font-bold leading-none tracking-tight">{to}</div>
                <div className="mt-1 text-xs font-medium text-white/70">{cityFor(to)}</div>
                {arrivalTime ? (
                  <div className="tabular mt-0.5 text-xs text-white/50">
                    {dayAndMonth(arrivalTime)} · {time(arrivalTime)}
                  </div>
                ) : null}
              </div>
            </div>

            {/* Detail row */}
            <div className="mt-5 grid grid-cols-6 gap-3 border-t border-dashed border-white/15 pt-4">
              <Field label="Passenger" value={pass.passengerName} span={2} />
              <Field label="Flight" value={pass.flightNumber} />
              {cabin ? <Field label="Class" value={cabin} /> : null}
              <Field label="Seat" value={pass.seatNumber} strong />
              <Field label="Gate" value={pass.gate ?? '—'} strong />
              <Field label="Group" value={pass.boardingGroup ?? '—'} strong />
              <Field label="Boarding" value={boarding} strong />
              <Field label="Terminal" value="—" />
              {record ? <Field label="Booking ref" value={record.bookingReference} /> : null}
            </div>
          </div>
        </div>

        {/* Perforation + tear-off stub */}
        <div className="relative w-44 shrink-0 bg-brand-50">
          {/* Notch punches at the seam. */}
          <span className="absolute -left-2 -top-2 h-4 w-4 rounded-full bg-white" />
          <span className="absolute -bottom-2 -left-2 h-4 w-4 rounded-full bg-white" />
          <span className="absolute inset-y-0 left-0 border-l-2 border-dashed border-brand-200" />

          <div className="flex h-full flex-col p-4">
            <div className="text-[10px] font-bold uppercase tracking-[0.15em] text-brand-700">Boarding pass</div>
            <div className="tabular mt-2 flex items-center gap-1.5 text-sm font-bold text-slate-900">
              {from} <span className="text-slate-400">→</span> {to}
            </div>
            <dl className="mt-3 space-y-1.5 text-xs">
              <StubRow label="Passenger" value={pass.passengerName} />
              <StubRow label="Flight" value={pass.flightNumber} />
              <StubRow label="Seat" value={pass.seatNumber} />
              <StubRow label="Gate" value={pass.gate ?? '—'} />
            </dl>

            {pass.barcodeToken ? (
              <div className="mt-auto pt-3">
                <div className="flex h-10 items-end gap-px overflow-hidden" role="img" aria-label="Barcode">
                  {[...pass.barcodeToken.slice(0, 60)].map((char, i) => (
                    <span key={i} className="flex-1 bg-slate-900" style={{ height: `${45 + (char.charCodeAt(0) % 55)}%` }} />
                  ))}
                </div>
                <p className="mt-1 truncate font-mono text-[8px] text-slate-400">{pass.boardingPassNumber}</p>
              </div>
            ) : null}
          </div>
        </div>
      </div>
    </div>
  );
}

function cityFor(code: string): string {
  return AIRPORTS.find((a) => a.code === code)?.city ?? code;
}

function cabinLabel(travelClass: string): string {
  return travelClass
    .split('_')
    .map((w) => w.charAt(0) + w.slice(1).toLowerCase())
    .join(' ');
}

function Field({
  label,
  value,
  strong = false,
  span = 1,
}: {
  label: string;
  value: string;
  strong?: boolean;
  span?: number;
}) {
  return (
    <div className={span === 2 ? 'col-span-2' : undefined}>
      <dt className="text-[9px] font-medium uppercase tracking-wider text-white/45">{label}</dt>
      <dd className={strong ? 'text-lg font-bold leading-tight' : 'truncate text-sm font-medium'}>{value}</dd>
    </div>
  );
}

function StubRow({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-[9px] font-medium uppercase tracking-wider text-slate-400">{label}</dt>
      <dd className="truncate font-medium text-slate-800">{value}</dd>
    </div>
  );
}
