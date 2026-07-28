import type { BoardingPass, CheckIn } from '../../api/checkin';
import { AIRPORTS } from '../../api/flights';
import { time } from '../../lib/format';
import { printBoardingPass } from './printable';

/**
 * The boarding pass (FRONTEND_MODULE.md §5 screen 10).
 *
 * <p>Styled as the classic red airline ticket: a red header band with the mark
 * and a dashed flight path, light-blue field boxes for passenger/from/to, big
 * red date and time, a red FLIGHT/GATE/SEAT block, a barcode, and a perforated
 * tear-off stub carrying the mini route. Data comes from the boarding pass, the
 * check-in record (route/date/class), and the flight (arrival) when present.
 */
const RED = '#e11b22';
const BLUE = '#cfe0f5';

export function BoardingPassCard({
  pass,
  record,
  arrivalTime: _arrivalTime,
}: {
  pass: BoardingPass;
  record?: CheckIn;
  arrivalTime?: string;
}) {
  const from = record?.originAirportCode ?? '—';
  const to = record?.destinationAirportCode ?? '—';
  const boardBefore = pass.boardingTime
    ? new Date(pass.boardingTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false })
    : '—';
  const mapBg: React.CSSProperties = {
    backgroundImage: 'radial-gradient(rgb(15 23 42 / 0.06) 1.4px, transparent 1.4px)',
    backgroundSize: '13px 13px',
  };

  return (
    <div className="overflow-x-auto">
      {/* Download lives ABOVE the ticket - not stamped onto the pass itself. */}
      <div className="mb-2 flex min-w-[760px] justify-end">
        <button
          type="button"
          onClick={() => printBoardingPass(pass, record, _arrivalTime)}
          className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 shadow-sm transition hover:border-red-300 hover:text-red-600"
          title="Download / print boarding pass"
        >
          <svg viewBox="0 0 24 24" className="h-3.5 w-3.5 fill-current" aria-hidden="true">
            <path d="M5 20h14v-2H5v2zM12 2v10.17l3.59-3.58L17 10l-5 5-5-5 1.41-1.41L12 12.17V2z" />
          </svg>
          Download boarding pass
        </button>
      </div>

      {/* One ticket: main coupon + tear-off stub joined by a dashed cut line. */}
      <div className="flex min-w-[760px] items-stretch overflow-hidden rounded-2xl bg-white shadow-[var(--shadow-lift)] ring-1 ring-slate-200">
        {/* ---------------- Main coupon ---------------- */}
        <div className="relative flex-1">
          <div className="pointer-events-none absolute inset-0" style={mapBg} />

          {/* Red header */}
          <div className="relative flex items-center justify-between px-5 py-3 text-white" style={{ background: RED }}>
            <div className="flex items-center gap-2.5">
              <Logo />
              <span className="text-2xl font-extrabold italic tracking-tight">SkyBook</span>
            </div>
            <div className="flex items-center gap-3">
              <span className="hidden items-center gap-[3px] sm:flex">
                {Array.from({ length: 22 }).map((_, i) => (
                  <span key={i} className="h-1 w-1 rounded-full bg-white/85" />
                ))}
              </span>
              <PlaneIcon className="h-5 w-6 fill-white" />
            </div>
          </div>

          {/* Body */}
          <div className="relative flex gap-5 px-5 py-4">
            {/* Left: passenger / from / to */}
            <div className="w-[38%] space-y-2.5">
              <FieldBox icon={<PersonIcon />} label="PASSENGER NAME" value={pass.passengerName} />
              <FieldBox icon={<TakeoffIcon />} label="FROM" value={cityFor(from)} />
              <FieldBox icon={<LandingIcon />} label="TO" value={cityFor(to)} />
            </div>

            {/* Center: date / time + flight/gate/seat */}
            <div className="flex flex-1 flex-col justify-center">
              <div className="flex items-center gap-3">
                <span className="rounded bg-slate-200 px-2 py-1 text-sm font-extrabold tracking-wide text-slate-600">DATE</span>
                <span className="tabular text-4xl font-extrabold tracking-wide" style={{ color: RED }}>
                  {record ? dotDate(record.departureTime) : '—'}
                </span>
              </div>
              <div className="mt-2.5 flex items-center gap-3">
                <span className="rounded bg-slate-200 px-2 py-1 text-sm font-extrabold tracking-wide text-slate-600">TIME</span>
                <span className="tabular text-4xl font-extrabold tracking-wide" style={{ color: RED }}>
                  {record ? time(record.departureTime) : '—'}
                </span>
              </div>

              <div className="mt-4 flex w-fit overflow-hidden rounded">
                <RedCell label="FLIGHT" value={pass.flightNumber} />
                <RedCell label="GATE" value={pass.gate ?? '—'} />
                <RedCell label="SEAT" value={pass.seatNumber} last />
              </div>
            </div>

            {/* Barcode */}
            <div className="flex flex-col items-center justify-center">
              <div className="flex h-[104px] items-stretch gap-px" role="img" aria-label="Boarding pass barcode">
                {(pass.barcodeToken ? [...pass.barcodeToken.slice(0, 34)] : []).map((c, i) => (
                  <span key={i} className="bg-slate-900" style={{ width: `${1 + (c.charCodeAt(0) % 3)}px` }} />
                ))}
              </div>
            </div>
          </div>

          {/* Important note */}
          <div className="relative border-t border-slate-200/70 px-5 py-2.5">
            <p className="font-mono text-xs text-slate-600">
              <span className="mr-1 font-bold" style={{ color: RED }}>ⓘ IMPORTANT NOTE:</span>
              You should be at the boarding gate before {boardBefore}.
            </p>
          </div>
        </div>

        {/* ---------------- Perforated tear-off stub ---------------- */}
        {/* Notch punches at the top and bottom of the seam make the dashed
            border read as a real tear-off perforation, not just a divider. */}
        <div className="relative w-56 shrink-0 border-l-2 border-dashed border-slate-300 bg-white">
          <span className="absolute -left-[7px] -top-[7px] h-3.5 w-3.5 rounded-full bg-white ring-1 ring-slate-200" />
          <span className="absolute -bottom-[7px] -left-[7px] h-3.5 w-3.5 rounded-full bg-white ring-1 ring-slate-200" />
          <div className="pointer-events-none absolute inset-0" style={mapBg} />

          <div className="relative flex items-center justify-center gap-2 px-4 py-3 text-white" style={{ background: RED }}>
            <span className="tracking-[0.2em] opacity-80">···</span>
            <span className="text-lg font-extrabold tracking-wide">BOARDING PASS</span>
            <span className="tracking-[0.2em] opacity-80">···</span>
          </div>

          <div className="relative space-y-2.5 p-4">
            <FieldBox icon={<PersonIcon />} label="PASSENGER" value={pass.passengerName} small />
            <FieldBox icon={<TakeoffIcon />} label="FROM" value={cityFor(from)} small />
            <FieldBox icon={<LandingIcon />} label="TO" value={cityFor(to)} small />

            <div className="flex items-center gap-2 rounded px-2 py-2" style={{ background: BLUE }}>
              <span className="rounded px-1.5 py-1 text-center text-white" style={{ background: '#1d38d8' }}>
                <span className="block text-[8px] font-bold leading-none">FLIGHT</span>
                <span className="tabular block text-xs font-bold leading-tight">{pass.flightNumber}</span>
              </span>
              <span className="tabular flex items-center gap-1 text-2xl font-extrabold text-slate-900">
                {from} <span style={{ color: RED }}>→</span> {to}
              </span>
            </div>

            <div className="flex items-center gap-2 pt-1">
              <Logo dark />
              <span className="text-lg font-extrabold italic tracking-tight text-slate-900">SkyBook</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

/* helpers */
function cityFor(code: string): string {
  return AIRPORTS.find((a) => a.code === code)?.city ?? code;
}
function dotDate(iso: string): string {
  const [d] = iso.split('T');
  const [y, m, day] = d.split('-');
  return `${day}.${m}.${y.slice(2)}`;
}

function FieldBox({
  icon,
  label,
  value,
  small = false,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  small?: boolean;
}) {
  return (
    <div>
      <div className="flex items-center gap-1.5 text-[11px] font-bold tracking-wide text-slate-800">
        <span className="text-slate-700">{icon}</span>
        {label}
      </div>
      <div
        className={
          'mt-1 truncate rounded font-mono text-slate-800 ' + (small ? 'px-2 py-1 text-xs' : 'px-3 py-1.5 text-sm')
        }
        style={{ background: BLUE }}
      >
        {value}
      </div>
    </div>
  );
}

function RedCell({ label, value, last = false }: { label: string; value: string; last?: boolean }) {
  return (
    <div
      className={'px-4 py-1.5 text-center text-white ' + (last ? '' : 'border-r border-white/40')}
      style={{ background: RED }}
    >
      <div className="text-[10px] font-bold tracking-wide">{label}</div>
      <div className="tabular text-lg font-extrabold leading-tight">{value}</div>
    </div>
  );
}

/* icons */
function Logo({ dark = false }: { dark?: boolean }) {
  return (
    <svg viewBox="0 0 40 32" className="h-7 w-8" aria-hidden="true">
      <path d="M2 22 C 10 6, 26 2, 38 4 C 28 8, 18 14, 10 26 Z" fill={dark ? '#1d38d8' : '#ffffff'} />
      <path d="M6 27 C 14 14, 26 8, 37 8 C 27 14, 18 20, 12 30 Z" fill={dark ? '#e11b22' : '#bcd0ee'} opacity="0.9" />
    </svg>
  );
}
function PlaneIcon({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" className={className} aria-hidden="true">
      <path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z" transform="rotate(90 12 12)" />
    </svg>
  );
}
function PersonIcon() {
  return (
    <svg viewBox="0 0 24 24" className="h-3.5 w-3.5 fill-current" aria-hidden="true">
      <path d="M12 12a5 5 0 1 0-5-5 5 5 0 0 0 5 5zm0 2c-4 0-8 2-8 5v1h16v-1c0-3-4-5-8-5z" />
    </svg>
  );
}
function TakeoffIcon() {
  return (
    <svg viewBox="0 0 24 24" className="h-3.5 w-3.5 fill-current" aria-hidden="true">
      <path d="M2.5 19h19v2h-19v-2zM22 9.2c-.2-.8-1-1.3-1.8-1.1l-5 1.3L8.4 4l-1.9.5 3.9 6.8-4.7 1.3-1.9-1.5-1.4.4 1.8 3.2 1.1.5 15.3-4.1c.9-.2 1.4-1 1.2-1.9z" />
    </svg>
  );
}
function LandingIcon() {
  return (
    <svg viewBox="0 0 24 24" className="h-3.5 w-3.5 fill-current" aria-hidden="true">
      <path d="M2.5 19h19v2h-19v-2zM21.5 12.7c-.2.8-1 1.3-1.8 1.1L4.4 9.7l-1.1-.5-.7-3.6 1.4-.4 1.5 1.9 4.7 1.3L7 1.7 8.9 1.2l4.6 6.6 5 1.3c.8.2 1.3 1 1 1.6z" />
    </svg>
  );
}
