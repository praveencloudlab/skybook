import type { BoardingPass } from '../../api/checkin';

/**
 * The boarding pass (FRONTEND_MODULE.md §5 screen 10).
 *
 * <p>The end of the passenger journey. Boarding itself is a gate operation and
 * the platform refuses a passenger who attempts it, so there is deliberately no
 * "board" action here - a button that always 403s would be worse than none.
 *
 * <p>Laid out like a real pass: the things a person needs while walking through
 * an airport - seat, gate, boarding group - are the largest, and the signed
 * token is rendered as a scannable strip rather than raw text.
 */
export function BoardingPassCard({ pass }: { pass: BoardingPass }) {
  return (
    <div className="notched overflow-hidden rounded-lg bg-white shadow-[0_1px_3px_rgb(15_23_42/0.12)] ring-1 ring-brand-200">
      <div className="flex items-center justify-between bg-gradient-to-r from-brand-800 to-brand-600 px-4 py-2.5 text-white">
        <span className="flex items-center gap-2 text-xs font-semibold tracking-widest uppercase">
          <svg viewBox="0 0 24 24" className="h-3.5 w-3.5 fill-white/90" aria-hidden="true">
            <path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z" />
          </svg>
          Boarding pass
        </span>
        <span className="font-mono text-xs text-white/80">{pass.boardingPassNumber}</span>
      </div>

      <div className="grid grid-cols-3 gap-4 px-4 py-4">
        <Cell label="Passenger" value={pass.passengerName} span />
        <Cell label="Flight" value={pass.flightNumber} />

        {/* Seat, gate and group are what a person actually looks for while
            moving through an airport, so they get the largest type. */}
        <Cell label="Seat" value={pass.seatNumber} big />
        <Cell label="Gate" value={pass.gate ?? '—'} big />
        <Cell label="Group" value={pass.boardingGroup ?? '—'} big />

        {pass.boardingTime ? (
          <Cell
            label="Boarding"
            value={new Date(pass.boardingTime).toLocaleTimeString([], {
              hour: '2-digit',
              minute: '2-digit',
            })}
            span
          />
        ) : null}
      </div>

      {pass.barcodeToken ? (
        <div className="border-t border-dashed border-slate-300 bg-slate-50/60 px-4 py-3">
          {/*
            A visual stand-in for a scannable code: the signed token rendered as
            bars. Deterministic from the token, so it looks like a real barcode
            rather than decoration, without pulling in a barcode library for what
            is a simulated gate.
          */}
          <div
            className="flex h-12 items-end gap-px overflow-hidden"
            role="img"
            aria-label="Boarding pass barcode"
          >
            {[...pass.barcodeToken.slice(0, 120)].map((char, index) => (
              <span
                key={index}
                className="flex-1 bg-slate-900"
                style={{ height: `${40 + (char.charCodeAt(0) % 60)}%` }}
              />
            ))}
          </div>
          <p className="mt-2 truncate font-mono text-[10px] text-slate-500">{pass.barcodeToken}</p>
        </div>
      ) : null}
    </div>
  );
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
      <dd className={big ? 'text-xl font-semibold text-slate-900' : 'text-sm text-slate-900'}>
        {value}
      </dd>
    </div>
  );
}
