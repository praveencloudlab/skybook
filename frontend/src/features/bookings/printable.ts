import type { Booking } from '../../api/bookings';
import { AIRPORTS, type Flight } from '../../api/flights';
import type { BoardingPass, CheckIn } from '../../api/checkin';
import { TRAVEL_CLASS_LABELS, type TravelClass } from '../../api/quotes';
import { dayMonthYear, money, time, timeShift } from '../../lib/format';
import { qrSvg } from '../../lib/qr';

/**
 * Client-side "download" for the boarding pass and e-ticket (FRONTEND_MODULE.md
 * Modules 10/13 - Download PDF).
 *
 * <p>There is no server PDF endpoint (the emailed ticket is rendered inside
 * notification-service), so rather than fake a download or add a cross-service
 * PDF API, the pass/ticket is rendered into a self-contained print window and
 * the browser's own "Save as PDF" does the rest. It works offline, matches what
 * is on screen, and needs no new backend.
 */

const CSS = `
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: 'Segoe UI', system-ui, -apple-system, sans-serif; color: #0f172a; padding: 32px; -webkit-print-color-adjust: exact; print-color-adjust: exact; }
  .doc { max-width: 720px; margin: 0 auto; }
  .navy { background: #0d1633; color: #fff; }
  .pad { padding: 20px 24px; }
  .row { display: flex; justify-content: space-between; align-items: center; }
  .brand { font-size: 12px; font-weight: 700; letter-spacing: 2px; text-transform: uppercase; }
  .mono { font-family: 'Consolas', monospace; }
  .card { border: 1px solid #e2e8f0; border-radius: 14px; overflow: hidden; }
  .grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
  .label { font-size: 10px; letter-spacing: 1px; text-transform: uppercase; color: #64748b; }
  .val { font-size: 15px; font-weight: 600; }
  .big { font-size: 22px; font-weight: 700; }
  .muted { color: #64748b; font-size: 13px; }
  .bars { display: flex; align-items: flex-end; gap: 1px; height: 56px; margin-top: 12px; }
  .bars span { flex: 1; background: #0f172a; }
  table { width: 100%; border-collapse: collapse; margin-top: 8px; }
  td, th { text-align: left; padding: 8px 0; border-bottom: 1px solid #f1f5f9; font-size: 13px; }
  th { color: #64748b; font-weight: 600; font-size: 11px; text-transform: uppercase; letter-spacing: 1px; }
  .total { display: flex; justify-content: space-between; padding-top: 12px; font-weight: 700; }
  .hr { border-top: 1px dashed #cbd5e1; margin: 16px 0; }
  @media print { body { padding: 0; } }
`;

/**
 * Download the document as a real, self-contained HTML file (lands in Downloads).
 *
 * <p>Earlier attempts used {@code window.open} (pop-up-blocked, appeared to do
 * nothing) and a hidden-iframe {@code print()} (a Save-as-PDF dialog, not a
 * download). A Blob + {@code <a download>} is the only mechanism that reliably
 * produces an actual file in every browser with no pop-up and no dialog. The
 * file carries a print-on-open button so it converts to PDF in one click.
 */
function open(title: string, body: string): void {
  const filename = `SkyBook-${title.replace(/[^\w.-]+/g, '-')}.html`;
  const html =
    `<!doctype html><html><head><meta charset="utf-8"><title>${title}</title><style>${CSS}` +
    `.print-bar{position:fixed;top:10px;right:10px;} .print-bar button{font:600 13px system-ui;background:#2547eb;color:#fff;border:0;border-radius:8px;padding:8px 14px;cursor:pointer;} @media print{.print-bar{display:none;}}</style></head>` +
    `<body><div class="print-bar"><button onclick="window.print()">Save as PDF / Print</button></div>` +
    `<div class="doc">${body}</div></body></html>`;

  const blob = new Blob([html], { type: 'text/html;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  window.setTimeout(() => URL.revokeObjectURL(url), 5000);
}

function bars(token: string): string {
  return (
    '<div class="bars" role="img" aria-label="barcode">' +
    [...token.slice(0, 140)].map((c) => `<span style="height:${40 + (c.charCodeAt(0) % 60)}%"></span>`).join('') +
    '</div>'
  );
}


function cityFor(code: string): string {
  return AIRPORTS.find((a) => a.code === code)?.city ?? code;
}

export function printBoardingPass(pass: BoardingPass, record?: CheckIn, _arrivalTime?: string): void {
  const RED = '#e11b22';
  const BLUE = '#cfe0f5';
  const TBA = 'TBA';
  const DASH = '&mdash;';

  const fromCode = pass.originAirportCode ?? record?.originAirportCode ?? '—';
  const toCode = pass.destinationAirportCode ?? record?.destinationAirportCode ?? '—';
  const pnr = pass.bookingReference ?? record?.bookingReference ?? DASH;
  const cabin = record?.travelClass
    ? TRAVEL_CLASS_LABELS[record.travelClass as TravelClass] ?? record.travelClass
    : DASH;
  const departDate = record ? dayMonthYear(record.departureTime) : DASH;
  const departTime = record ? time(record.departureTime) : DASH;
  // Boarding must read EARLIER than departure. checkin-service currently stamps
  // boardingTime with the departure clock, so unless the server sends a
  // genuinely earlier time, boarding is derived as departure - 40 minutes (and
  // the gate advisory 30 minutes before that).
  const serverBoard = pass.boardingTime ? time(pass.boardingTime) : null;
  const boardTime =
    serverBoard && record && serverBoard < departTime
      ? serverBoard
      : record
        ? timeShift(record.departureTime, -40)
        : serverBoard ?? DASH;
  const gateBy =
    serverBoard && record && serverBoard < departTime
      ? timeShift(pass.boardingTime as string, -30)
      : record
        ? timeShift(record.departureTime, -70)
        : DASH;
  const gate = pass.gate ?? TBA;
  const group = pass.boardingGroup ?? DASH;
  const issued = pass.issuedAt ? `${dayMonthYear(pass.issuedAt)} ${time(pass.issuedAt)}` : DASH;
  const qr = pass.token ? qrSvg(pass.token) : '';

  // A label/value cell for the operational grid: small grey caps over a bold value.
  const fld = (label: string, value: string, opts: { mono?: boolean; accent?: boolean; span?: number } = {}) =>
    `<td colspan="${opts.span ?? 1}" style="vertical-align:top;padding:0 10px 0 0;">
       <div style="font-size:10px;font-weight:700;letter-spacing:.5px;color:#94a3b8;">${label}</div>
       <div style="font-size:15px;font-weight:800;color:${opts.accent ? RED : '#0f172a'};${opts.mono ? "font-family:'Courier New',monospace;" : ''}">${value}</div>
     </td>`;
  const airport = (code: string, name: string) =>
    `<td style="width:44%;background:${BLUE};border-radius:8px;padding:9px 13px;">
       <div style="font-size:24px;font-weight:800;line-height:1;color:#0f172a;font-family:'Courier New',monospace;">${code}</div>
       <div style="margin-top:4px;font-size:12px;color:#334155;">${name}</div>
     </td>`;
  const stubFld = (label: string, value: string, opts: { mono?: boolean; accent?: boolean } = {}) =>
    `<td style="vertical-align:top;padding:0;">
       <div style="font-size:9px;font-weight:700;letter-spacing:.3px;color:#94a3b8;">${label}</div>
       <div style="font-size:13px;font-weight:800;color:${opts.accent ? RED : '#0f172a'};${opts.mono ? "font-family:'Courier New',monospace;" : ''}">${value}</div>
     </td>`;

  // One rounded container clips the red headers to the top edge, the two coupons
  // sit flush with a dashed cut line between them, and the whole document is
  // self-contained (the QR is inline SVG, no external request) so it prints and
  // works offline.
  //
  // The .bp override matters: the shared print stylesheet gives every table a
  // margin-top and every td padding + a bottom border (meant for the e-ticket's
  // itinerary table). On the pass those leaked in as a white gap above the red
  // header and stray divider lines - all layout paddings here are inline, so
  // the defaults are zeroed.
  const body = `
    <style>.bp table{margin:0;}.bp td,.bp th{padding:0;border-bottom:none;}</style>
    <div class="bp" style="border:1px solid #e5e7eb;border-radius:16px;overflow:hidden;background:#fff;font-family:'Segoe UI',system-ui,sans-serif;">
      <table style="width:100%;border-collapse:collapse;">
        <tr>
          <!-- Main coupon -->
          <td style="width:70%;vertical-align:top;padding:0;">
            <div style="background:${RED};color:#fff;">
              <table style="width:100%;height:56px;border-collapse:collapse;"><tr>
                <td style="vertical-align:middle;padding:0 22px;font-size:22px;font-weight:800;font-style:italic;">&#9992; SkyBook</td>
                <td style="vertical-align:middle;text-align:center;font-size:14px;font-weight:800;letter-spacing:3px;">BOARDING PASS</td>
                <td style="vertical-align:middle;text-align:right;padding:0 22px;">
                  <span style="background:rgba(255,255,255,.2);border-radius:5px;padding:4px 11px;font-size:12px;font-weight:800;letter-spacing:1px;text-transform:uppercase;">${cabin}</span>
                </td>
              </tr></table>
            </div>

            <table style="width:100%;border-collapse:collapse;padding:0;">
              <tr>
                <td style="padding:16px 22px 4px;vertical-align:bottom;">
                  <div style="font-size:10px;font-weight:700;letter-spacing:1px;color:#94a3b8;">PASSENGER</div>
                  <div style="font-size:24px;font-weight:800;letter-spacing:1px;color:#0f172a;">${pass.passengerName.toUpperCase()}</div>
                </td>
                <td style="padding:16px 22px 4px;text-align:right;vertical-align:bottom;">
                  <div style="font-size:10px;font-weight:700;letter-spacing:1px;color:#94a3b8;">BOOKING REF (PNR)</div>
                  <div style="font-size:21px;font-weight:800;font-family:'Courier New',monospace;color:${RED};">${pnr}</div>
                </td>
              </tr>
            </table>

            <table style="width:100%;border-collapse:collapse;margin:6px 0;"><tr>
              <td style="padding:0 22px;">
                <table style="width:100%;border-collapse:separate;border-spacing:0;"><tr>
                  ${airport(fromCode, cityFor(fromCode))}
                  <td style="text-align:center;color:${RED};font-size:22px;width:34px;">&#9992;</td>
                  ${airport(toCode, cityFor(toCode))}
                </tr></table>
              </td>
            </tr></table>

            <table style="width:100%;border-collapse:collapse;padding:0;">
              <tr>
                <td style="padding:10px 0 0 22px;">
                  <table style="width:100%;border-collapse:collapse;">
                    <tr>${fld('FLIGHT', pass.flightNumber, { mono: true })}${fld('DATE', departDate)}${fld('DEPARTS', departTime)}${fld('BOARDING', boardTime, { accent: true })}</tr>
                    <tr><td colspan="4" style="height:14px;"></td></tr>
                    <tr>${fld('GATE', gate)}${fld('SEAT', pass.seatNumber, { mono: true })}${fld('CABIN', cabin)}${fld('BOARDING GROUP', group)}</tr>
                  </table>
                </td>
              </tr>
            </table>

            <table style="width:100%;border-collapse:collapse;margin-top:14px;border-top:1px solid #eef1f5;">
              <tr>
                <td style="padding:11px 22px;font-family:'Courier New',monospace;font-size:11px;color:#64748b;">
                  <span style="color:${RED};font-weight:700;">&#9432; NOTICE:</span> Please arrive at the boarding gate by ${gateBy}, 30 minutes before boarding begins at ${boardTime}. The gate closes before departure and late passengers may be offloaded.
                </td>
                <td style="padding:11px 22px;text-align:right;white-space:nowrap;font-family:'Courier New',monospace;font-size:11px;color:#64748b;">Issued ${issued}</td>
              </tr>
            </table>
          </td>

          <!-- Perforated tear-off stub -->
          <td style="width:30%;vertical-align:top;padding:0;border-left:2px dashed #cbd5e1;">
            <div style="background:${RED};color:#fff;">
              <table style="width:100%;height:56px;border-collapse:collapse;"><tr>
                <td style="vertical-align:middle;text-align:center;font-size:15px;font-weight:800;letter-spacing:1px;">&middot;&middot;&middot; BOARDING PASS &middot;&middot;&middot;</td>
              </tr></table>
            </div>
            <div style="padding:16px 18px;">
              <div style="font-size:9px;font-weight:700;color:#94a3b8;">PASSENGER</div>
              <div style="font-size:18px;font-weight:800;color:#0f172a;margin-bottom:12px;">${pass.passengerName.toUpperCase()}</div>

              <table style="width:100%;border-collapse:collapse;margin-bottom:12px;"><tr>
                ${stubFld('FLIGHT', pass.flightNumber, { mono: true })}
                <td style="text-align:right;font-family:'Courier New',monospace;font-size:22px;font-weight:800;color:#0f172a;">${fromCode}<span style="color:${RED};">&rarr;</span>${toCode}</td>
              </tr></table>

              <table style="width:100%;border-collapse:collapse;margin-bottom:14px;"><tr>
                ${stubFld('SEAT', pass.seatNumber, { mono: true })}
                ${stubFld('GRP', group)}
                ${stubFld('BOARD', boardTime, { accent: true })}
              </tr></table>

              ${qr ? `<div style="width:118px;height:118px;margin:0 auto 8px;">${qr}</div>` : ''}

              <div style="text-align:center;font-family:'Courier New',monospace;font-size:11px;letter-spacing:1px;color:#334155;">${pass.boardingPassNumber}</div>
              <div style="text-align:center;font-size:11px;color:#64748b;margin-top:2px;">PNR <b>${pnr}</b></div>
            </div>
          </td>
        </tr>
      </table>
    </div>`;
  open(`Boarding pass ${pass.boardingPassNumber}`, body);
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
function ddMon(iso: string): string {
  const [d] = iso.split('T');
  const [y, m, day] = d.split('-');
  return `${day}${MONTHS[Number(m) - 1] ?? m}${y}`;
}
function durationHM(dep: string, arr: string): string {
  const mins = (Date.parse(`${arr}Z`) - Date.parse(`${dep}Z`)) / 60_000;
  if (!Number.isFinite(mins) || mins <= 0) {
    return '';
  }
  return `${String(Math.floor(mins / 60)).padStart(2, '0')}:${String(Math.round(mins % 60)).padStart(2, '0')}`;
}
function addDaysMon(iso: string, days: number): string {
  const dt = new Date(`${iso.slice(0, 10)}T00:00:00Z`);
  dt.setUTCDate(dt.getUTCDate() + days);
  return ddMon(dt.toISOString());
}
/** Default max baggage allowance per cabin (checked + cabin), shown on the ticket. */
const BAGGAGE: Record<string, string> = {
  ECONOMY: '25 kg checked + 7 kg cabin',
  PREMIUM_ECONOMY: '30 kg checked + 7 kg cabin',
  BUSINESS: '40 kg checked + 10 kg cabin',
  FIRST: '50 kg checked + 10 kg cabin',
};

/**
 * Electronic ticket receipt (FRONTEND_MODULE.md Modules 10) - laid out like a
 * carrier's itinerary receipt: maroon wave header, passenger + barcode block,
 * an "ELECTRONIC TICKET RECEIPT" band, the grey itinerary table with per-segment
 * detail rows, and the fare-condition footnotes.
 */
export function printETicket(
  booking: Booking,
  flightsById: Record<number, Flight>,
  _currency = 'USD',
): void {
  const MAROON = '#5a1836';
  const p0 = booking.passengers[0];
  const paxLines = booking.passengers
    // One line per TRAVELLER, not per per-segment row.
    .filter((p) => (p.segmentIndex ?? 0) === 0)
    .map((p) => `${p.firstName} ${p.lastName} (ADT)`)
    .join('<br>');
  // Real e-ticket numbers once issued (125-XXXXXXXXXX, one per traveller);
  // the legacy derived number only for pre-ticketing bookings.
  const ticketNo = booking.tickets?.length
    ? booking.tickets
        .map((t) => `${t.ticketNumber.slice(0, 3)}-${t.ticketNumber.slice(3)}`)
        .join('<br>')
    : `157 ${2100000000 + booking.id}`;

  const cabin = p0 ? TRAVEL_CLASS_LABELS[p0.travelClass].toUpperCase() : 'ECONOMY';
  const fareBasis = p0 ? `${p0.travelClass[0]}${p0.fareType.slice(0, 3)}${booking.bookingReference}`.toUpperCase() : '';
  const classCode = p0 ? p0.fareType[0] : '';
  const baggage = (p0 && BAGGAGE[p0.travelClass]) || '25 kg checked + 7 kg cabin';

  // Every leg of the journey - a round trip prints BOTH directions, each as
  // its own coupon rows. Legacy bookings without segments fall back to the
  // booking-level flight.
  const multi = (booking.segments?.length ?? 0) > 1;
  const segs = booking.segments?.length
    ? booking.segments
    : [{ id: 0, segmentIndex: 0, flightId: booking.flightId, status: 'UPCOMING' as const }];

  const legLabel = (i: number) => (i === 0 ? 'OUTBOUND' : i === 1 ? 'RETURN' : `LEG ${i + 1}`);
  const segmentRows = (flight: Flight, index: number, cancelled: boolean) => `
      ${multi ? `
      <tr>
        <td colspan="6" style="padding:8px 12px;background:#f3eef1;color:${MAROON};font-weight:800;font-size:12px;letter-spacing:1px;">
          ${legLabel(index)} &middot; ${flight.originAirportCode} &rarr; ${flight.destinationAirportCode}
          ${cancelled ? ' &mdash; <span style="color:#b42318;">CANCELLED</span>' : ''}
        </td>
      </tr>` : ''}
      <tr${cancelled ? ' style="opacity:.55;"' : ''}>
        <td style="padding:10px 12px;vertical-align:top;">
          <div><b style="font-size:15px;">${flight.originAirportCode}</b> ${cityFor(flight.originAirportCode).toUpperCase()}</div>
          <div style="color:#333;">Terminal: M</div>
        </td>
        <td style="padding:10px 12px;vertical-align:top;">
          <div><b style="font-size:15px;">${flight.destinationAirportCode}</b> ${cityFor(flight.destinationAirportCode).toUpperCase()}</div>
          <div style="color:#333;">Terminal: 4</div>
        </td>
        <td style="padding:10px 12px;vertical-align:top;">${flight.airlineCode}${flight.flightNumber.replace(/\D/g, '') || flight.flightNumber}</td>
        <td style="padding:10px 12px;vertical-align:top;"><b>${time(flight.departureTime)}</b><br>${ddMon(flight.departureTime)}</td>
        <td style="padding:10px 12px;vertical-align:top;"><b>${time(flight.arrivalTime)}</b><br>${ddMon(flight.arrivalTime)}</td>
        <td style="padding:10px 12px;vertical-align:top;">${timeShift(flight.departureTime, -60)}</td>
      </tr>
      <tr style="background:#ececec;font-size:12px;color:#222;${cancelled ? 'opacity:.55;' : ''}">
        <td colspan="2" style="padding:10px 12px;vertical-align:top;">
          <div>Class: <b>${classCode}</b></div>
          <div>Cabin: ${cabin}</div>
          <div>Max baggage (4): ${baggage}</div>
          <div>Fare basis: ${fareBasis}</div>
        </td>
        <td colspan="2" style="padding:10px 12px;vertical-align:top;">
          <div>Operated by: SKYBOOK</div>
          <div>Marketed by: SKYBOOK</div>
          <div>Booking status (1): ${cancelled ? 'CANCELLED' : 'OK'}</div>
          <div>Seat${booking.passengers.filter((p) => (p.segmentIndex ?? 0) === index && !p.cancelled && p.seatNumber).length === 1 ? '' : 's'}:
            ${booking.passengers
              .filter((p) => (p.segmentIndex ?? 0) === index && !p.cancelled)
              .map((p) => p.seatNumber ?? '&mdash;')
              .join(', ') || '&mdash;'}</div>
        </td>
        <td colspan="2" style="padding:10px 12px;vertical-align:top;">
          <div>NVB (2): ${ddMon(flight.departureTime)}</div>
          <div>NVA (3): ${addDaysMon(flight.departureTime, 120)}</div>
          <div>Duration: ${durationHM(flight.departureTime, flight.arrivalTime)}</div>
        </td>
      </tr>`;

  const segment = segs
    .map((seg) => {
      const flight = flightsById[seg.flightId];
      return flight ? segmentRows(flight, seg.segmentIndex, seg.status === 'CANCELLED') : '';
    })
    .join('');

  const body = `
    <div style="font-family:Arial,Helvetica,sans-serif;color:#1a1a1a;font-size:13px;">
      <!-- Header -->
      <div style="position:relative;height:96px;overflow:hidden;background:linear-gradient(115deg,#3f0f24 0%,#6d1f43 45%,#9c3a66 100%);">
        <svg viewBox="0 0 900 96" preserveAspectRatio="none" style="position:absolute;inset:0;width:100%;height:100%;">
          <path d="M0,58 C230,86 470,20 900,52 L900,96 L0,96 Z" fill="#ffffff" opacity="0.10"/>
          <path d="M0,70 C260,40 540,92 900,44 L900,96 L0,96 Z" fill="#ffffff" opacity="0.07"/>
        </svg>
        <div style="position:absolute;left:22px;top:26px;color:#fff;font-style:italic;font-weight:700;font-size:19px;">Going places together</div>
        <div style="position:absolute;right:22px;top:22px;display:flex;align-items:center;gap:12px;color:#fff;">
          <span style="display:inline-flex;align-items:center;justify-content:center;width:40px;height:40px;border-radius:50%;background:rgba(255,255,255,.15);font-size:9px;font-weight:700;line-height:1.1;text-align:center;">SKY<br>ALLIANCE</span>
          <span style="font-size:26px;font-weight:800;letter-spacing:1px;">SkyBook &#9992;</span>
        </div>
      </div>

      <!-- Passenger + barcode block -->
      <table style="width:100%;border-collapse:collapse;margin-top:18px;">
        <tr>
          <td style="vertical-align:top;padding:0 16px;">
            <div style="margin-bottom:8px;"><b>Passenger:</b> ${paxLines}</div>
            <div style="margin-bottom:8px;"><b>Booking ref:</b> ${booking.bookingReference}</div>
            <div><b>Ticket number:</b> ${ticketNo}</div>
          </td>
          <td style="vertical-align:top;padding:0 16px;width:48%;">
            <div style="height:52px;">${bars(booking.bookingReference.repeat(8))}</div>
            <div style="margin-top:6px;"><b>Itinerary Printing Office:</b></div>
            <div>SKYBOOK DIGITAL, DIGITAL OFFICE</div>
            <div><b>Date:</b> ${ddMon(booking.bookingDate)}</div>
          </td>
        </tr>
      </table>

      <!-- ETR band -->
      <div style="background:${MAROON};color:#fff;font-weight:800;font-size:18px;letter-spacing:.5px;padding:11px 16px;margin-top:16px;">ELECTRONIC TICKET RECEIPT</div>

      <!-- Itinerary -->
      <table style="width:100%;border-collapse:collapse;margin-top:16px;font-size:13px;">
        <thead>
          <tr style="background:#d9d9d9;color:#333;text-align:left;font-size:12px;">
            <th style="padding:7px 12px;font-weight:700;">From</th>
            <th style="padding:7px 12px;font-weight:700;">To</th>
            <th style="padding:7px 12px;font-weight:700;">Flight</th>
            <th style="padding:7px 12px;font-weight:700;">Departure</th>
            <th style="padding:7px 12px;font-weight:700;">Arrival</th>
            <th style="padding:7px 12px;font-weight:700;">Last check-in</th>
          </tr>
        </thead>
        <tbody>${segment}</tbody>
      </table>
      <div style="border-top:2px solid #333;margin-top:0;"></div>

      <!-- Footnotes -->
      <div style="font-size:11px;color:#333;margin-top:12px;padding:0 4px;line-height:1.5;">
        <b>(1)</b> OK = Confirmed &nbsp; <b>(2)</b> NVB = Not valid before &nbsp; <b>(3)</b> NVA = Not valid after &nbsp;
        <b>(4)</b> Each passenger can check in a specific amount of baggage at no extra cost as indicated in the column baggage.
        For more information on baggage rules and restrictions, please visit
        <span style="color:#1d4ed8;text-decoration:underline;">skybook.example/baggage</span>.
      </div>
      <div style="font-size:11px;color:#666;margin-top:10px;padding:0 4px;">
        Total paid: <b>${money(booking.totalFare, _currency)}</b> &nbsp;·&nbsp; Status: ${booking.bookingStatus}
        ${booking.contact ? ' &nbsp;·&nbsp; Contact: ' + booking.contact.contactEmail : ''}
      </div>
    </div>`;
  open(`E-ticket ${booking.bookingReference}`, body);
}
