import type { Booking } from '../../api/bookings';
import { AIRPORTS, type Flight } from '../../api/flights';
import type { BoardingPass, CheckIn } from '../../api/checkin';
import { TRAVEL_CLASS_LABELS } from '../../api/quotes';
import { money, time } from '../../lib/format';

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

function dotDate(iso: string): string {
  const [d] = iso.split('T');
  const [y, m, day] = d.split('-');
  return `${day}.${m}.${y.slice(2)}`;
}

/** Vertical bars for the red-ticket barcode (full-height, varying width). */
function vbars(token: string): string {
  return (
    '<div style="display:flex;align-items:stretch;height:96px;">' +
    [...token.slice(0, 34)].map((c) => `<span style="width:${1 + (c.charCodeAt(0) % 3)}px;background:#0f172a;margin-right:1px;"></span>`).join('') +
    '</div>'
  );
}

export function printBoardingPass(pass: BoardingPass, record?: CheckIn, _arrivalTime?: string): void {
  const RED = '#e11b22';
  const BLUE = '#cfe0f5';
  const from = record?.originAirportCode ?? '—';
  const to = record?.destinationAirportCode ?? '—';
  const boarding = pass.boardingTime
    ? new Date(pass.boardingTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false })
    : '—';

  const box = (label: string, value: string) =>
    `<div style="margin-bottom:10px;">
       <div style="font-size:11px;font-weight:700;color:#1e293b;">${label}</div>
       <div style="margin-top:3px;background:${BLUE};border-radius:4px;padding:6px 10px;font-family:monospace;font-size:13px;color:#1e293b;">${value}</div>
     </div>`;
  const redCell = (label: string, value: string, last = false) =>
    `<td style="background:${RED};color:#fff;text-align:center;padding:6px 16px;${last ? '' : 'border-right:1px solid rgba(255,255,255,.4);'}">
       <div style="font-size:10px;font-weight:700;">${label}</div>
       <div style="font-size:18px;font-weight:800;font-family:monospace;">${value}</div>
     </td>`;

  const body = `
    <table style="width:100%;border-collapse:separate;border-spacing:10px 0;font-family:'Segoe UI',system-ui,sans-serif;">
      <tr>
        <!-- Main coupon -->
        <td style="width:70%;background:#fff;border:1px solid #e5e7eb;border-radius:14px;vertical-align:top;overflow:hidden;">
          <div style="background:${RED};color:#fff;padding:12px 18px;display:flex;align-items:center;justify-content:space-between;">
            <span style="font-size:22px;font-weight:800;font-style:italic;">&#9992; SkyBook</span>
            <span style="letter-spacing:3px;opacity:.85;">&middot; &middot; &middot; &middot; &middot; &middot; &#9992;</span>
          </div>
          <table style="width:100%;padding:16px 18px;">
            <tr>
              <td style="width:40%;vertical-align:top;">
                ${box('PASSENGER NAME', pass.passengerName)}
                ${box('FROM', cityFor(from))}
                ${box('TO', cityFor(to))}
              </td>
              <td style="vertical-align:middle;padding-left:12px;">
                <div style="display:flex;align-items:center;gap:12px;">
                  <span style="background:#e2e8f0;color:#475569;font-weight:800;border-radius:4px;padding:4px 8px;font-size:13px;">DATE</span>
                  <span style="color:${RED};font-size:34px;font-weight:800;font-family:monospace;">${record ? dotDate(record.departureTime) : '—'}</span>
                </div>
                <div style="display:flex;align-items:center;gap:12px;margin-top:10px;">
                  <span style="background:#e2e8f0;color:#475569;font-weight:800;border-radius:4px;padding:4px 8px;font-size:13px;">TIME</span>
                  <span style="color:${RED};font-size:34px;font-weight:800;font-family:monospace;">${record ? time(record.departureTime) : '—'}</span>
                </div>
                <table style="margin-top:14px;border-collapse:separate;border-spacing:0;border-radius:4px;overflow:hidden;">
                  <tr>${redCell('FLIGHT', pass.flightNumber)}${redCell('GATE', pass.gate ?? '—')}${redCell('SEAT', pass.seatNumber, true)}</tr>
                </table>
              </td>
              <td style="vertical-align:middle;text-align:center;width:70px;">${pass.barcodeToken ? vbars(pass.barcodeToken) : ''}</td>
            </tr>
          </table>
          <div style="border-top:1px solid #eef1f5;padding:8px 18px;font-family:monospace;font-size:12px;color:#64748b;">
            <span style="color:${RED};font-weight:700;">&#9432; IMPORTANT NOTE:</span> You should be at the boarding gate before ${boarding}.
          </div>
        </td>

        <!-- Tear-off stub -->
        <td style="width:30%;background:#fff;border:1px solid #e5e7eb;border-radius:14px;vertical-align:top;overflow:hidden;">
          <div style="background:${RED};color:#fff;text-align:center;padding:12px;font-size:16px;font-weight:800;letter-spacing:1px;">&middot;&middot;&middot; BOARDING PASS &middot;&middot;&middot;</div>
          <div style="padding:16px;">
            ${box('PASSENGER', pass.passengerName)}
            ${box('FROM', cityFor(from))}
            ${box('TO', cityFor(to))}
            <div style="display:flex;align-items:center;gap:8px;background:${BLUE};border-radius:4px;padding:8px;">
              <span style="background:#1d38d8;color:#fff;border-radius:3px;padding:4px 6px;text-align:center;">
                <span style="display:block;font-size:8px;font-weight:700;">FLIGHT</span>
                <span style="display:block;font-size:12px;font-weight:800;font-family:monospace;">${pass.flightNumber}</span>
              </span>
              <span style="font-size:22px;font-weight:800;color:#0f172a;font-family:monospace;">${from} <span style="color:${RED};">&rarr;</span> ${to}</span>
            </div>
            <div style="margin-top:12px;font-size:18px;font-weight:800;font-style:italic;color:#0f172a;">&#9992; SkyBook</div>
          </div>
        </td>
      </tr>
    </table>`;
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
function shiftTime(iso: string, deltaMinutes: number): string {
  const dt = new Date(`${iso.slice(0, 16)}:00Z`);
  if (Number.isNaN(dt.getTime())) {
    return '';
  }
  dt.setUTCMinutes(dt.getUTCMinutes() + deltaMinutes);
  return `${String(dt.getUTCHours()).padStart(2, '0')}:${String(dt.getUTCMinutes()).padStart(2, '0')}`;
}
function addDaysMon(iso: string, days: number): string {
  const dt = new Date(`${iso.slice(0, 10)}T00:00:00Z`);
  dt.setUTCDate(dt.getUTCDate() + days);
  return ddMon(dt.toISOString());
}
const BAGGAGE: Record<string, string> = {
  ECONOMY: '25K',
  PREMIUM_ECONOMY: '30K',
  BUSINESS: '40K',
  FIRST: '50K',
};

/**
 * Electronic ticket receipt (FRONTEND_MODULE.md Modules 10) - laid out like a
 * carrier's itinerary receipt: maroon wave header, passenger + barcode block,
 * an "ELECTRONIC TICKET RECEIPT" band, the grey itinerary table with per-segment
 * detail rows, and the fare-condition footnotes.
 */
export function printETicket(booking: Booking, flight: Flight | null, _currency = 'USD'): void {
  const MAROON = '#5a1836';
  const p0 = booking.passengers[0];
  const paxLines = booking.passengers
    .map((p) => `${p.firstName} ${p.lastName} (ADT)`)
    .join('<br>');
  const ticketNo = `157 ${2100000000 + booking.id}`;

  const cabin = p0 ? TRAVEL_CLASS_LABELS[p0.travelClass].toUpperCase() : 'ECONOMY';
  const fareBasis = p0 ? `${p0.travelClass[0]}${p0.fareType.slice(0, 3)}${booking.bookingReference}`.toUpperCase() : '';
  const classCode = p0 ? p0.fareType[0] : '';
  const baggage = p0 ? (BAGGAGE[p0.travelClass] ?? '25K') : '25K';

  const segment = flight
    ? `
      <tr>
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
        <td style="padding:10px 12px;vertical-align:top;">${shiftTime(flight.departureTime, -60)}</td>
      </tr>
      <tr style="background:#ececec;font-size:12px;color:#222;">
        <td colspan="2" style="padding:10px 12px;vertical-align:top;">
          <div>Class: <b>${classCode}</b></div>
          <div>Cabin: ${cabin}</div>
          <div>Baggage (4): ${baggage}</div>
          <div>Fare basis: ${fareBasis}</div>
        </td>
        <td colspan="2" style="padding:10px 12px;vertical-align:top;">
          <div>Operated by: SKYBOOK</div>
          <div>Marketed by: SKYBOOK</div>
          <div>Booking status (1): OK</div>
          <div>Frequent flyer number &nbsp;&mdash;</div>
        </td>
        <td colspan="2" style="padding:10px 12px;vertical-align:top;">
          <div>NVB (2): ${ddMon(flight.departureTime)}</div>
          <div>NVA (3): ${addDaysMon(flight.departureTime, 120)}</div>
          <div>Duration: ${durationHM(flight.departureTime, flight.arrivalTime)}</div>
        </td>
      </tr>`
    : '';

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
