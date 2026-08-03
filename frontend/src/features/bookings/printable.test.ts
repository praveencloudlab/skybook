import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Booking } from '../../api/bookings';
import type { Flight } from '../../api/flights';
import { printETicket } from './printable';

/**
 * The downloadable e-ticket is a generated document - regressions here are
 * invisible to component tests, so the file itself is captured (via the
 * object-URL the download anchor uses) and asserted on.
 */
describe('printETicket', () => {
  let captured: string;

  beforeEach(() => {
    captured = '';
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: (blob: Blob) => {
        // Blob.text() is async but the content is needed synchronously for
        // the assertion - FileReaderSync isn't in jsdom, so stash the parts.
        void blob.text().then((t) => {
          captured = t;
        });
        return 'blob:test';
      },
      revokeObjectURL: () => {},
    });
    // The download <a> must not actually navigate jsdom.
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  const flight = (id: number, num: string, from: string, to: string, dep: string): Flight =>
    ({
      id,
      flightNumber: num,
      airlineCode: 'EK',
      originAirportCode: from,
      destinationAirportCode: to,
      departureTime: dep,
      arrivalTime: dep.slice(0, 11) + '18:00:00',
      status: 'SCHEDULED',
    }) as unknown as Flight;

  const booking: Booking = {
    id: 188,
    bookingReference: 'SBGBPX',
    flightId: 10,
    bookingStatus: 'CONFIRMED',
    bookingDate: '2026-07-30T12:00:00',
    totalFare: 210,
    ownerSubject: 'x@example.com',
    segments: [
      { id: 1, segmentIndex: 0, flightId: 10, status: 'UPCOMING' },
      { id: 2, segmentIndex: 1, flightId: 20, status: 'UPCOMING' },
    ],
    passengers: [
      {
        id: 1, passengerId: 42, segmentIndex: 0, flightId: 10,
        firstName: 'Gbp', lastName: 'Email', title: 'Mr', passengerType: 'ADULT',
        seatNumber: '15B', travelClass: 'ECONOMY', fareType: 'FLEXI',
        baseFare: 110, seatSurcharge: 0, extraBags: 0, baggageFee: 0, fare: 110,
      },
      {
        id: 2, passengerId: 42, segmentIndex: 1, flightId: 20,
        firstName: 'Gbp', lastName: 'Email', title: 'Mr', passengerType: 'ADULT',
        seatNumber: '16A', travelClass: 'ECONOMY', fareType: 'FLEXI',
        baseFare: 100, seatSurcharge: 0, extraBags: 0, baggageFee: 0, fare: 100,
      },
    ],
    payment: { paymentStatus: 'PAID', amount: 210, currency: 'GBP', externalPaymentReference: 'PAY-1' },
    tickets: [
      {
        id: 5, ticketNumber: '1250000018801', passengerId: 42, status: 'ISSUED',
        issuedAt: '2026-07-30T12:01:00',
        coupons: [
          { couponNumber: 1, segmentIndex: 0, bookingPassengerId: 1, status: 'OPEN' },
          { couponNumber: 2, segmentIndex: 1, bookingPassengerId: 2, status: 'OPEN' },
        ],
      },
    ],
  };

  it('prints a GBP booking with pound signs in the ledger, passenger rows and total', async () => {
    printETicket(booking, {
      10: flight(10, 'EK001', 'LHR', 'DXB', '2026-08-30T08:25:00'),
      20: flight(20, 'EK0019', 'DXB', 'LHR', '2026-09-06T17:20:00'),
    });
    await vi.waitFor(() => expect(captured).not.toBe(''));

    // Both legs, the real ticket number, and pound amounts everywhere the
    // document states money - never a bare USD.
    expect(captured).toContain('OUTBOUND');
    expect(captured).toContain('RETURN');
    expect(captured).toContain('125-0000018801');
    expect(captured).toContain('£110.00'); // outbound leg fare line
    expect(captured).toContain('£210.00'); // ledger TOTAL
    expect(captured).toContain('FARE CALCULATION');
    expect(captured).not.toMatch(/US\$|USD /);
  });
});
