import type { BoardingPass, CheckIn } from '../../api/checkin';
import { dayAndMonth, time } from '../../lib/format';
import { printBoardingPass } from './printable';

/**
 * The boarding pass (FRONTEND_MODULE.md §5 screen 10).
 *
 * <p>The end of the passenger journey. Boarding itself is a gate operation and
 * the platform refuses a passenger who attempts it, so there is deliberately no
 * "board" action here.
 *
 * <p>The pass carries everything a person needs at the airport: route and date,
 * flight, cabin and PNR, and the large seat/gate/group/boarding block, with the
 * signed token as a scannable strip. Route/date/class/PNR come from the check-in
 * record (the pass endpoint itself doesn't repeat them).
 */
export function BoardingPassCard({ pass, record }: { pass: BoardingPass; record?: CheckIn }) {
  return (
    <div className="notched overflow-hidden rounded-lg bg-white shadow-[0_1px_3px_rgb(15_23_42/0.12)] ring-1 ring-brand-200">
      <div className="flex items-center justify-between bg-gradient-to-r from-brand-800 to-brand-600 px-4 py-2.5 text-white">
        <span className="flex items-center gap-2 text-xs font-semibold tracking-widest uppercase">
          <svg viewBox="0 0 24 24" className="h-3.5 w-3.5 fill-white/90" aria-hidden="true">
            <path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z" />
          </svg>
          Boarding pass
        </span>
        <div className="flex items-center gap-3">
          <span className="font-mono text-xs text-white/80">{pass.boardingPassNumber}</span>
          <button
            type="button"
            onClick={() => printBoardingPass(pass, record)}
            className="inline-flex items-center gap-1 rounded-md bg-white/15 px-2 py-1 text-[11px] font-semibold text-white transition hover:bg-white/25"
            title="Download / print boarding pass"
          >
            <svg viewBox="0 0 24 24" className="h-3 w-3 fill-current" aria-hidden="true">
              <path d="M5 20h14v-2H5v2zM12 2v10.17l3.59-3.58L17 10l-5 5-5-5 1.41-1.41L12 12.17V2z" />
            </svg>
            Download
          </button>
        </div>
      </div>

      {/* Route + date, from the check-in record. */}
      {record ? (
        <div className="flex items-center gap-4 border-b border-slate-100 bg-slate-50/60 px-4 py-3">
          <div>
            <div className="tabular text-xl font-semibold leading-none text-slate-900">{time(record.departureTime)}</div>
            <div className="mt-0.5 text-xs font-medium tracking-wide text-slate-500">{record.originAirportCode}</div>
          </div>
          <div className="flex flex-1 flex-col items-center">
            <span className="text-[11px] text-slate-400">{dayAndMonth(record.departureTime)}</span>
            <span className="route-line my-1 w-full" />
            <span className="text-[11px] text-slate-400">Direct</span>
          </div>
          <div className="text-right">
            <div className="text-xs font-medium tracking-wide text-slate-500">{record.destinationAirportCode}</div>
          </div>
        </div>
      ) : null}

      <div className="grid grid-cols-3 gap-4 px-4 py-4">
        <Cell label="Passenger" value={pass.passengerName} span />
        <Cell label="Flight" value={pass.flightNumber} />
        {record ? <Cell label="Class" value={cabinLabel(record.travelClass)} /> : null}
        {record ? <Cell label="Booking ref" value={record.bookingReference} /> : null}

        <Cell label="Seat" value={pass.seatNumber} big />
        <Cell label="Gate" value={pass.gate ?? '—'} big />
        <Cell label="Group" value={pass.boardingGroup ?? '—'} big />

        {pass.boardingTime ? (
          <Cell
            label="Boarding"
            value={new Date(pass.boardingTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
            span
          />
        ) : null}
      </div>

      {pass.barcodeToken ? (
        <div className="border-t border-dashed border-slate-300 bg-slate-50/60 px-4 py-3">
          <div className="flex h-12 items-end gap-px overflow-hidden" role="img" aria-label="Boarding pass barcode">
            {[...pass.barcodeToken.slice(0, 120)].map((char, index) => (
              <span key={index} className="flex-1 bg-slate-900" style={{ height: `${40 + (char.charCodeAt(0) % 60)}%` }} />
            ))}
          </div>
          <p className="mt-2 truncate font-mono text-[10px] text-slate-500">{pass.barcodeToken}</p>
        </div>
      ) : null}
    </div>
  );
}

function cabinLabel(travelClass: string): string {
  return travelClass
    .split('_')
    .map((w) => w.charAt(0) + w.slice(1).toLowerCase())
    .join(' ');
}

function Cell({
  label,
  value,
  big = false,
  span = false,
}: {
  label: string;
  value: string;
  big?: boolean;
  span?: boolean;
}) {
  return (
    <div className={span ? 'col-span-2' : undefined}>
      <dt className="text-[10px] font-medium tracking-wider text-slate-500 uppercase">{label}</dt>
      <dd className={big ? 'text-xl font-semibold text-slate-900' : 'text-sm text-slate-900'}>{value}</dd>
    </div>
  );
}
