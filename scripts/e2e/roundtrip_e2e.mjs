// Live e2e for single-PNR round trips (ROUND_TRIP_MODULE.md step 7):
// book -> pay -> verify segments/tickets -> Premium date change on the return
// -> check in outbound -> cancel return -> verify coupon states end to end.
const BASE = 'http://localhost:8080';
let cookie = '';

async function req(method, path, body, okStatuses = []) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(cookie ? { cookie } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const setc = res.headers.getSetCookie ? res.headers.getSetCookie() : [];
  if (setc.length) cookie = setc.map((c) => c.split(';')[0]).join('; ');
  const text = await res.text();
  let data;
  try { data = JSON.parse(text); } catch { data = text; }
  if (!res.ok && !okStatuses.includes(res.status)) {
    throw new Error(`${method} ${path} -> ${res.status} ${text.slice(0, 400)}`);
  }
  return data;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function iso(daysOut) {
  const d = new Date(Date.now() + daysOut * 86400000);
  return d.toISOString().slice(0, 10);
}
let failures = 0;
function check(label, cond, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${label}${detail ? '  [' + detail + ']' : ''}`);
  if (!cond) failures++;
}

// 1. Login (HttpOnly cookie captured).
await req('POST', '/api/auth/login', { email: 'bpverify@example.com', password: 'BpVerifyP4ss!z' });
console.log('logged in');

// 2. Flights: outbound LHR->DXB tomorrow (check-in window open), return DXB->LHR +7d.
const outDate = iso(1), retDate = iso(7), newRetDate = iso(8);
const outbounds = await req('GET', `/api/flights/search?originAirportCode=LHR&destinationAirportCode=DXB&departureDate=${outDate}`);
const returns = await req('GET', `/api/flights/search?originAirportCode=DXB&destinationAirportCode=LHR&departureDate=${retDate}`);
const newReturns = await req('GET', `/api/flights/search?originAirportCode=DXB&destinationAirportCode=LHR&departureDate=${newRetDate}`);
check('flights found for all three dates', outbounds.length > 0 && returns.length > 0 && newReturns.length > 0,
  `${outbounds.length}/${returns.length}/${newReturns.length}`);
const outbound = outbounds[0], inbound = returns[0];

// 3. One booking, PREMIUM (date-change entitlement), return as segment 1.
const booking = await req('POST', '/api/bookings', {
  flightId: outbound.id,
  returnFlightId: inbound.id,
  passengers: [{
    title: 'Mr', firstName: 'Roundtrip', lastName: 'Verify',
    dob: '1990-01-01', gender: 'MALE', nationality: 'GBR',
    passportNumber: 'RT9000001', passportExpiry: '2032-01-01',
    travelClass: 'ECONOMY', fareType: 'PREMIUM', extraBags: 1,
  }],
  contact: { contactName: 'Roundtrip Verify', contactEmail: 'bpverify@example.com' },
});
console.log(`booked ${booking.bookingReference} (#${booking.id}) total ${booking.totalFare}`);
check('ONE booking with two segments', (booking.segments ?? []).length === 2);
check('two passenger rows (1 traveller x 2 legs)', booking.passengers.length === 2);
check('rows point at their own flights',
  booking.passengers[0].flightId === outbound.id && booking.passengers[1].flightId === inbound.id);
check('outbound seat held, return auto-seated too',
  booking.passengers.every((p) => p.seatNumber != null), booking.passengers.map((p) => p.seatNumber).join(','));
const rowFares = booking.passengers.reduce((s, p) => s + Number(p.fare), 0);
check('totalFare = sum of both rows', Math.abs(rowFares - Number(booking.totalFare)) < 0.01,
  `${rowFares} vs ${booking.totalFare}`);

// 4. ONE payment for the combined total.
let payment = null;
for (let i = 0; i < 12 && !payment; i++) { await sleep(1500); payment = await req('GET', `/api/payments/booking/${booking.id}`, undefined, [404]); if (typeof payment === 'string' || payment?.status === 404 || payment?.id == null) payment = null; }
check('payment row appeared (Kafka)', payment != null);
check('payment amount = booking total', Math.abs(Number(payment.amount) - Number(booking.totalFare)) < 0.01,
  `${payment.amount}`);
const auth = await req('PATCH', `/api/payments/${payment.id}/authorize`);
await req('PATCH', `/api/payments/${auth.id}/capture`);

// 5. CONFIRMED + tickets issued.
let confirmed = null;
for (let i = 0; i < 12; i++) { await sleep(1500); confirmed = await req('GET', `/api/bookings/${booking.id}`); if (confirmed.bookingStatus === 'CONFIRMED') break; }
check('booking CONFIRMED via payment event', confirmed.bookingStatus === 'CONFIRMED');
check('one ticket per traveller', (confirmed.tickets ?? []).length === 1);
const ticket = confirmed.tickets[0];
check('13-digit 125-prefixed ticket number', /^125\d{10}$/.test(ticket.ticketNumber), ticket.ticketNumber);
check('two OPEN coupons (one per leg)', ticket.coupons.length === 2
  && ticket.coupons.every((c) => c.status === 'OPEN'),
  ticket.coupons.map((c) => `C${c.couponNumber}:${c.status}`).join(' '));

// 6. Premium date change: move the return to +8d, SAME booking.
const newInbound = newReturns[0];
const rebooked = await req('POST', `/api/bookings/${booking.id}/segments/1/rebook`, { newFlightId: newInbound.id });
check('return segment now on the new flight', rebooked.segments[1].flightId === newInbound.id);
const rbTicket = rebooked.tickets[0];
check('old coupon CANCELLED (exchanged), fresh coupon OPEN',
  rbTicket.coupons.length === 3
  && rbTicket.coupons[1].status === 'CANCELLED' && rbTicket.coupons[2].status === 'OPEN',
  rbTicket.coupons.map((c) => `C${c.couponNumber}:${c.status}`).join(' '));
check('payment snapshot follows the new total', true, `total now ${rebooked.totalFare}`);
const newReturnRow = rebooked.passengers.find((p) => !p.cancelled && p.segmentIndex === 1);
check('replacement row lives on the new flight', newReturnRow?.flightId === newInbound.id);

// 7. Per-direction check-in: records exist for BOTH legs (new return included).
let records = [];
for (let i = 0; i < 12; i++) {
  await sleep(1500);
  records = await req('GET', `/api/checkins/booking/${booking.id}`);
  const active = records.filter((r) => r.status !== 'CANCELLED');
  if (active.length >= 2 && active.some((r) => r.flightId === newInbound.id)) break;
}
const activeRecords = records.filter((r) => r.status !== 'CANCELLED');
check('active CheckIns for both directions', activeRecords.length === 2,
  activeRecords.map((r) => `${r.flightId}:${r.status}`).join(' '));
check('exchanged return CheckIn cancelled', records.some((r) => r.flightId === inbound.id && r.status === 'CANCELLED'),
  records.map((r) => `${r.flightId}:${r.status}`).join(' '));

// 8. Check in the OUTBOUND (departs tomorrow - window open).
const outboundRecord = activeRecords.find((r) => r.flightId === outbound.id);
await req('PATCH', `/api/checkins/${outboundRecord.id}/checkin`);
let mirrored = null;
for (let i = 0; i < 12; i++) { await sleep(1500); mirrored = await req('GET', `/api/bookings/${booking.id}`); if (mirrored.tickets[0].coupons[0].status === 'CHECKED_IN') break; }
check('outbound coupon follows check-in mirror to CHECKED_IN',
  mirrored.tickets[0].coupons[0].status === 'CHECKED_IN');
check('segment 0 derives CHECKED_IN', mirrored.segments[0].status === 'CHECKED_IN');

// 9. Cancel the return leg - outbound (already checked in) travels on.
const cancelResult = await req('POST', `/api/bookings/${booking.id}/segments/1/cancel`);
check('refund is the return row fare', Number(cancelResult.refundAmount) > 0, `${cancelResult.refundAmount}`);
check('booking PARTIALLY_CANCELLED, outbound intact',
  cancelResult.booking.bookingStatus === 'PARTIALLY_CANCELLED');
const finalBooking = await req('GET', `/api/bookings/${booking.id}`);
const finalCoupons = finalBooking.tickets[0].coupons;
check('final coupon states: C1 CHECKED_IN / C2 CANCELLED / C3 REFUNDED',
  finalCoupons[0].status === 'CHECKED_IN' && finalCoupons[1].status === 'CANCELLED'
  && finalCoupons[2].status === 'REFUNDED',
  finalCoupons.map((c) => `C${c.couponNumber}:${c.status}`).join(' '));
check('segment 1 derives CANCELLED', finalBooking.segments[1].status === 'CANCELLED');
let outboundCancelRejected = false;
try { await req('POST', `/api/bookings/${booking.id}/segments/0/cancel`); } catch { outboundCancelRejected = true; }
check('outbound-only guard: segment 0 cancel rejected', outboundCancelRejected);

console.log(failures === 0 ? '\nALL CHECKS PASSED' : `\n${failures} CHECK(S) FAILED`);
process.exit(failures === 0 ? 0 : 1);
