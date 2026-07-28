import type { BoardingPass, CheckIn } from '../../api/checkin';
import { AIRPORTS } from '../../api/flights';
import { TRAVEL_CLASS_LABELS, type TravelClass } from '../../api/quotes';
import { dayMonthYear, time, timeShift } from '../../lib/format';
import { qrSvg } from '../../lib/qr';
import { printBoardingPass } from './printable';

/**
 * The boarding pass (FRONTEND_MODULE.md §5 screen 10).
 *
 * <p>A full IATA-style pass: a red header band, the passenger and PNR up top,
 * the route in light-blue field boxes, then a grid carrying every operational
 * detail (flight, date, departure/boarding times, terminal, gate, seat, cabin,
 * boarding group, sequence, ticket), a scannable QR generated from the pass's
 * signed token, and a tear-off stub. Fields the platform does not actually
 * hold - terminal, sequence, ticket number - render as honest placeholders
 * rather than fabricated values.
 */
const RED = '#e11b22';
const BLUE = '#cfe0f5';
const TBA = 'To Be Announced';
const DASH = '—';

export function BoardingPassCard({
  pass,
  record,
  arrivalTime: _arrivalTime,
}: {
  pass: BoardingPass;
  record?: CheckIn;
  arrivalTime?: string;
}) {
  const fromCode = pass.originAirportCode ?? record?.originAirportCode ?? DASH;
  const toCode = pass.destinationAirportCode ?? record?.destinationAirportCode ?? DASH;
  const pnr = pass.bookingReference ?? record?.bookingReference ?? DASH;
  const cabin = cabinLabel(record?.travelClass);
  const departDate = record?.departureTime ? dayMonthYear(record.departureTime) : DASH;
  const departTime = record?.departureTime ? time(record.departureTime) : DASH;
  const boardTime = pass.boardingTime ? time(pass.boardingTime) : DASH;
  // Gate-arrival advisory: 30 minutes before boarding starts, not boarding itself.
  const gateBy = pass.boardingTime ? timeShift(pass.boardingTime, -30) : DASH;
  const gate = pass.gate ?? TBA;
  const group = pass.boardingGroup ?? DASH;
  const issued = pass.issuedAt ? `${dayMonthYear(pass.issuedAt)} ${time(pass.issuedAt)}` : DASH;
  const qr = pass.token ? qrSvg(pass.token) : '';

  const mapBg: React.CSSProperties = {
    backgroundImage: 'radial-gradient(rgb(15 23 42 / 0.05) 1.4px, transparent 1.4px)',
    backgroundSize: '13px 13px',
  };

  return (
    <div className="overflow-x-auto">
      {/* Download lives ABOVE the ticket - not stamped onto the pass itself. */}
      <div className="mb-2 flex min-w-[640px] justify-end">
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
      {/* min-w is a floor for tiny windows only - at the page's normal width the
          pass fits without horizontal scrolling. */}
      <div className="flex min-w-[640px] items-stretch overflow-hidden rounded-2xl bg-white shadow-[var(--shadow-lift)] ring-1 ring-slate-200">
        {/* ---------------- Main coupon ---------------- */}
        <div className="relative flex-1">
          <div className="pointer-events-none absolute inset-0" style={mapBg} />

          {/* Red header - fixed height so it lines up with the stub header. */}
          <div className="relative flex h-14 items-center justify-between px-5 text-white" style={{ background: RED }}>
            <div className="flex items-center gap-2.5">
              <Logo />
              <span className="text-2xl font-extrabold italic tracking-tight">SkyBook</span>
            </div>
            <span className="text-sm font-extrabold tracking-[0.25em]">BOARDING PASS</span>
            <span className="rounded bg-white/20 px-3 py-1 text-xs font-extrabold uppercase tracking-wider">{cabin}</span>
          </div>

          {/* Passenger + PNR */}
          <div className="relative flex items-end justify-between px-5 pb-2 pt-3.5">
            <div>
              <div className="text-[10px] font-bold tracking-wide text-slate-400">PASSENGER</div>
              <div className="text-2xl font-extrabold tracking-wide text-slate-900">{pass.passengerName.toUpperCase()}</div>
            </div>
            <div className="text-right">
              <div className="text-[10px] font-bold tracking-wide text-slate-400">BOOKING REF (PNR)</div>
              <div className="tabular text-xl font-extrabold" style={{ color: RED }}>{pnr}</div>
            </div>
          </div>

          {/* Route */}
          <div className="relative flex items-center gap-3 px-5 pb-3">
            <AirportBox code={fromCode} name={cityFor(fromCode)} />
            <PlaneIcon className="h-5 w-6 shrink-0" style={{ fill: RED }} />
            <AirportBox code={toCode} name={cityFor(toCode)} />
          </div>

          {/* Operational detail grid */}
          <div className="relative grid grid-cols-4 gap-x-3 gap-y-3.5 px-5 pb-3">
            <Field label="FLIGHT" value={pass.flightNumber} mono />
            <Field label="DATE" value={departDate} />
            <Field label="DEPARTS" value={departTime} />
            <Field label="BOARDING" value={boardTime} accent />

            <Field label="GATE" value={gate} small={gate === TBA} />
            <Field label="TERMINAL" value={TBA} small />
            <Field label="SEAT" value={pass.seatNumber} mono />
            <Field label="CABIN" value={cabin} />

            <Field label="BOARDING GROUP" value={group} />
            <Field label="SEQUENCE" value={DASH} mono />
            <div className="col-span-2">
              <FieldInner label="TICKET NUMBER" value={DASH} mono />
            </div>
          </div>

          {/* Security notice + issue stamp */}
          <div className="relative flex items-center justify-between gap-4 border-t border-slate-200/70 px-5 py-2.5">
            <p className="font-mono text-xs text-slate-600">
              <span className="mr-1 font-bold" style={{ color: RED }}>ⓘ NOTICE:</span>
              Please arrive at the boarding gate by {gateBy}, 30 minutes before boarding begins at {boardTime}. The gate closes before departure and late passengers may be offloaded.
            </p>
            <p className="whitespace-nowrap font-mono text-[11px] text-slate-500">Issued {issued}</p>
          </div>
        </div>

        {/* ---------------- Perforated tear-off stub ---------------- */}
        <div className="relative w-64 shrink-0 border-l-2 border-dashed border-slate-300 bg-white">
          <span className="absolute -left-[7px] -top-[7px] h-3.5 w-3.5 rounded-full bg-white ring-1 ring-slate-200" />
          <span className="absolute -bottom-[7px] -left-[7px] h-3.5 w-3.5 rounded-full bg-white ring-1 ring-slate-200" />
          <div className="pointer-events-none absolute inset-0" style={mapBg} />

          <div className="relative flex h-14 items-center justify-center gap-2 px-4 text-white" style={{ background: RED }}>
            <span className="tracking-[0.2em] opacity-80">···</span>
            <span className="text-base font-extrabold tracking-wide">BOARDING PASS</span>
            <span className="tracking-[0.2em] opacity-80">···</span>
          </div>

          <div className="relative space-y-3 p-4">
            <div>
              <div className="text-[9px] font-bold tracking-wide text-slate-400">PASSENGER</div>
              <div className="text-base font-extrabold text-slate-900">{pass.passengerName.toUpperCase()}</div>
            </div>

            <div className="flex items-center justify-between">
              <StubField label="FLIGHT" value={pass.flightNumber} mono />
              <span className="tabular flex items-center gap-1 text-xl font-extrabold text-slate-900">
                {fromCode} <span style={{ color: RED }}>→</span> {toCode}
              </span>
            </div>

            <div className="grid grid-cols-4 gap-2">
              <StubField label="SEAT" value={pass.seatNumber} mono />
              <StubField label="GRP" value={group} />
              <StubField label="SEQ" value={DASH} mono />
              <StubField label="BOARD" value={boardTime} accent />
            </div>

            {qr ? (
              <div className="flex justify-center pt-1">
                <div className="h-28 w-28" dangerouslySetInnerHTML={{ __html: qr }} />
              </div>
            ) : null}

            <div className="text-center">
              <div className="tabular font-mono text-[11px] tracking-wide text-slate-600">{pass.boardingPassNumber}</div>
              <div className="text-[11px] text-slate-500">PNR <span className="font-bold">{pnr}</span></div>
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
function cabinLabel(travelClass?: string): string {
  if (!travelClass) {
    return '—';
  }
  return TRAVEL_CLASS_LABELS[travelClass as TravelClass] ?? travelClass;
}

function AirportBox({ code, name }: { code: string; name: string }) {
  return (
    <div className="flex-1 rounded-lg px-3.5 py-2" style={{ background: BLUE }}>
      <div className="tabular text-2xl font-extrabold leading-none text-slate-900">{code}</div>
      <div className="mt-1 truncate text-xs text-slate-600">{name}</div>
    </div>
  );
}

function Field({
  label,
  value,
  mono = false,
  accent = false,
  small = false,
}: {
  label: string;
  value: string;
  mono?: boolean;
  accent?: boolean;
  small?: boolean;
}) {
  return <FieldInner label={label} value={value} mono={mono} accent={accent} small={small} />;
}
function FieldInner({
  label,
  value,
  mono = false,
  accent = false,
  small = false,
}: {
  label: string;
  value: string;
  mono?: boolean;
  accent?: boolean;
  small?: boolean;
}) {
  return (
    <div>
      <div className="text-[10px] font-bold tracking-wide text-slate-400">{label}</div>
      <div
        className={
          'font-extrabold leading-tight ' +
          (small ? 'text-sm ' : 'text-base ') +
          (mono ? 'tabular font-mono ' : '')
        }
        style={{ color: accent ? RED : '#0f172a' }}
      >
        {value}
      </div>
    </div>
  );
}

function StubField({ label, value, mono = false, accent = false }: { label: string; value: string; mono?: boolean; accent?: boolean }) {
  return (
    <div>
      <div className="text-[9px] font-bold tracking-wide text-slate-400">{label}</div>
      <div
        className={'text-sm font-extrabold leading-tight ' + (mono ? 'tabular font-mono ' : '')}
        style={{ color: accent ? RED : '#0f172a' }}
      >
        {value}
      </div>
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
function PlaneIcon({ className, style }: { className?: string; style?: React.CSSProperties }) {
  return (
    <svg viewBox="0 0 24 24" className={className} style={style} aria-hidden="true">
      <path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z" transform="rotate(90 12 12)" />
    </svg>
  );
}
