import type { Booking } from '../../api/bookings';
import { AIRPORTS, type Flight } from '../../api/flights';
import type { BoardingPass, CheckIn } from '../../api/checkin';
import { TRAVEL_CLASS_LABELS, FARE_TYPE_LABELS } from '../../api/quotes';
import { dayAndMonth, duration, money, time } from '../../lib/format';

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

function open(title: string, body: string): void {
  const w = window.open('', '_blank', 'width=760,height=920');
  if (!w) {
    // Pop-up blocked - the only honest thing is to say so.
    alert('Please allow pop-ups for this site to download.');
    return;
  }
  w.document.write(
    `<!doctype html><html><head><meta charset="utf-8"><title>${title}</title><style>${CSS}</style></head>` +
      `<body><div class="doc">${body}</div><script>window.onload=function(){setTimeout(function(){window.print();},150);};</script></body></html>`,
  );
  w.document.close();
}

function bars(token: string): string {
  return (
    '<div class="bars" role="img" aria-label="barcode">' +
    [...token.slice(0, 140)].map((c) => `<span style="height:${40 + (c.charCodeAt(0) % 60)}%"></span>`).join('') +
    '</div>'
  );
}

/** When online check-in opens: 24h before departure, in the flight's own clock. */
function checkInOpensText(flight: Flight): string {
  const base = new Date(`${flight.departureTime.slice(0, 16)}:00Z`);
  if (Number.isNaN(base.getTime())) {
    return '';
  }
  base.setUTCHours(base.getUTCHours() - 24);
  const iso = base.toISOString().slice(0, 16);
  return `${dayAndMonth(iso)} at ${time(iso)}`;
}

function cityFor(code: string): string {
  return AIRPORTS.find((a) => a.code === code)?.city ?? code;
}

const bpField = (label: string, value: string, big = false) =>
  `<td style="vertical-align:top;padding-right:10px;">
     <div style="font-size:9px;letter-spacing:1px;text-transform:uppercase;color:rgba(255,255,255,.5);">${label}</div>
     <div style="font-weight:${big ? 700 : 600};font-size:${big ? 17 : 13}px;">${value}</div>
   </td>`;

const stubRow = (label: string, value: string) =>
  `<div style="margin-bottom:7px;">
     <div style="font-size:9px;letter-spacing:1px;text-transform:uppercase;color:#94a3b8;">${label}</div>
     <div style="font-weight:600;color:#1e293b;">${value}</div>
   </div>`;

export function printBoardingPass(pass: BoardingPass, record?: CheckIn, arrivalTime?: string): void {
  const boarding = pass.boardingTime
    ? new Date(pass.boardingTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : '—';
  const cabin = record
    ? record.travelClass.split('_').map((w) => w[0] + w.slice(1).toLowerCase()).join(' ')
    : '';
  const from = record?.originAirportCode ?? '—';
  const to = record?.destinationAirportCode ?? '—';

  // A real ticket: main coupon (FROM -> plane -> TO + details) and a perforated
  // tear-off stub carrying the barcode.
  const body = `
    <table style="width:100%;border-collapse:separate;border-spacing:0;font-family:'Segoe UI',system-ui,sans-serif;">
      <tr>
        <td style="width:70%;background:#0d1633;color:#fff;padding:22px;vertical-align:top;border-radius:14px 0 0 14px;">
          <div style="font-size:11px;letter-spacing:2px;text-transform:uppercase;opacity:.8;font-weight:700;">&#9992; SkyBook &middot; Boarding pass</div>
          <table style="width:100%;margin-top:16px;">
            <tr>
              <td style="vertical-align:bottom;">
                <div style="font-size:10px;opacity:.5;text-transform:uppercase;letter-spacing:1px;">From</div>
                <div style="font-size:38px;font-weight:800;line-height:1;">${from}</div>
                <div style="font-size:12px;opacity:.75;margin-top:3px;">${cityFor(from)}</div>
                ${record ? `<div style="font-size:11px;opacity:.55;">${dayAndMonth(record.departureTime)} &middot; ${time(record.departureTime)}</div>` : ''}
              </td>
              <td style="width:34%;padding:0 10px;vertical-align:bottom;padding-bottom:10px;">
                <table style="width:100%;border-collapse:collapse;"><tr>
                  <td style="border-bottom:1px dashed rgba(255,255,255,.35);font-size:0;line-height:0;">&nbsp;</td>
                  <td style="padding:0 5px;color:rgba(255,255,255,.9);font-size:20px;white-space:nowrap;">&#9992;</td>
                  <td style="border-bottom:1px dashed rgba(255,255,255,.35);font-size:0;line-height:0;">&nbsp;</td>
                </tr></table>
              </td>
              <td style="text-align:right;vertical-align:bottom;">
                <div style="font-size:10px;opacity:.5;text-transform:uppercase;letter-spacing:1px;">To</div>
                <div style="font-size:38px;font-weight:800;line-height:1;">${to}</div>
                <div style="font-size:12px;opacity:.75;margin-top:3px;">${cityFor(to)}</div>
                ${arrivalTime ? `<div style="font-size:11px;opacity:.55;">${dayAndMonth(arrivalTime)} &middot; ${time(arrivalTime)}</div>` : ''}
              </td>
            </tr>
          </table>
          <table style="width:100%;margin-top:18px;border-top:1px dashed rgba(255,255,255,.18);padding-top:14px;">
            <tr>
              ${bpField('Passenger', pass.passengerName)}
              ${bpField('Flight', pass.flightNumber)}
              ${cabin ? bpField('Class', cabin) : ''}
              ${bpField('Seat', pass.seatNumber, true)}
              ${bpField('Gate', pass.gate ?? '—', true)}
              ${bpField('Group', pass.boardingGroup ?? '—', true)}
              ${bpField('Boarding', boarding, true)}
              ${bpField('Terminal', '—')}
            </tr>
          </table>
        </td>
        <td style="width:30%;background:#eef3ff;padding:18px;vertical-align:top;border-left:2px dashed #c7d5f5;border-radius:0 14px 14px 0;">
          <div style="font-size:10px;font-weight:700;letter-spacing:1px;text-transform:uppercase;color:#2547eb;">Boarding pass</div>
          <div style="font-size:17px;font-weight:800;margin-top:8px;color:#0f172a;">${from} &rarr; ${to}</div>
          <div style="margin-top:12px;">
            ${stubRow('Passenger', pass.passengerName)}
            ${stubRow('Flight', pass.flightNumber)}
            ${stubRow('Seat', pass.seatNumber)}
            ${stubRow('Gate', pass.gate ?? '—')}
          </div>
          ${pass.barcodeToken ? `<div style="margin-top:14px;">${bars(pass.barcodeToken)}<div style="font-family:monospace;font-size:9px;color:#64748b;margin-top:5px;">${pass.boardingPassNumber}</div></div>` : ''}
        </td>
      </tr>
    </table>`;
  open(`Boarding pass ${pass.boardingPassNumber}`, body);
}

export function printETicket(booking: Booking, flight: Flight | null, currency = 'USD'): void {
  const flightBlock = flight
    ? `<div class="hr"></div>
       <div class="row">
         <div class="label">Flight</div>
         <div class="muted">${dayAndMonth(flight.departureTime)}</div>
       </div>
       <div class="row" style="align-items:flex-start;margin-top:6px">
         <div>
           <div class="big">${time(flight.departureTime)}</div>
           <div class="muted">${flight.originAirportCode}</div>
         </div>
         <div style="text-align:center;flex:1;padding:0 16px">
           <div class="muted">${duration(flight.departureTime, flight.arrivalTime)} · Direct</div>
           <div style="border-top:1px dashed #cbd5e1;margin:8px 0"></div>
           <div class="mono muted" style="font-size:11px">${flight.airlineCode} ${flight.flightNumber}</div>
         </div>
         <div style="text-align:right">
           <div class="big">${time(flight.arrivalTime)}</div>
           <div class="muted">${flight.destinationAirportCode}</div>
         </div>
       </div>
       ${checkInOpensText(flight) ? `<div class="muted" style="margin-top:10px;font-size:12px">🕐 Online check-in opens 24 hours before departure — around <b>${checkInOpensText(flight)}</b>.</div>` : ''}`
    : '';

  const rows = booking.passengers
    .map(
      (p, i) => `<tr>
        <td>${i + 1}. ${p.firstName} ${p.lastName}</td>
        <td>${TRAVEL_CLASS_LABELS[p.travelClass]} · ${FARE_TYPE_LABELS[p.fareType]}</td>
        <td>${p.seatNumber ?? 'At check-in'}</td>
        <td style="text-align:right">${money(p.fare ?? p.baseFare, currency)}</td>
      </tr>`,
    )
    .join('');

  const contact = booking.contact
    ? `<div class="hr"></div>
       <div class="label">Contact</div>
       <div class="muted" style="margin-top:4px">
         ${booking.contact.contactName} · ${booking.contact.contactEmail}${booking.contact.contactPhone ? ' · ' + booking.contact.contactPhone : ''}
       </div>`
    : '';

  const body = `
    <div class="card">
      <div class="navy pad row">
        <span class="brand">✈ SkyBook · E-ticket / Itinerary receipt</span>
        <span class="mono" style="font-size:12px;opacity:.8">${booking.bookingReference}</span>
      </div>
      <div class="pad">
        <div class="row">
          <div>
            <div class="label">Booking reference</div>
            <div class="mono big" style="letter-spacing:3px">${booking.bookingReference}</div>
          </div>
          <div style="text-align:right">
            <div class="label">Status</div>
            <div class="val">${booking.bookingStatus}</div>
            <div class="muted" style="font-size:12px">Booked ${dayAndMonth(booking.bookingDate)}</div>
          </div>
        </div>

        ${flightBlock}

        <div class="hr"></div>
        <div class="label">Passengers</div>
        <table>
          <thead><tr><th>Name</th><th>Cabin &amp; fare</th><th>Seat</th><th style="text-align:right">Fare</th></tr></thead>
          <tbody>${rows}</tbody>
        </table>
        <div class="total"><span>Total paid</span><span>${money(booking.totalFare, currency)}</span></div>

        ${contact}

        <div class="hr"></div>
        <div class="label">Fare rules (summary)</div>
        <div class="muted" style="margin-top:4px;font-size:12px;line-height:1.5">
          Saver — cancellable, fee applies, partial refund. Flexi — more generous refund.
          Premium — fully flexible. A captured payment is refunded to the original method on cancellation.
          Carry a valid passport; check in online 24 hours before departure.
        </div>

        ${bars(booking.bookingReference.repeat(6))}
        <p class="mono muted" style="margin-top:6px;font-size:10px;text-align:center">
          ${booking.bookingReference} · This is an itinerary receipt, not a boarding pass.
        </p>
      </div>
    </div>`;
  open(`E-ticket ${booking.bookingReference}`, body);
}
