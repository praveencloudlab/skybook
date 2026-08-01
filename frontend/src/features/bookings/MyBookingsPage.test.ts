import { describe, expect, it } from 'vitest';
import { groupOf } from './MyBookingsPage';
import type { Booking } from '../../api/bookings';
import type { Flight } from '../../api/flights';

/**
 * The lifecycle grouping on My trips: cancelled / no show / checked in /
 * pending check-in / completed, derived from passenger check-in state and
 * the LAST leg's departure.
 */

const HOUR = 3600_000;

function flightAt(offsetMs: number, id = 1): Flight {
  const dep = new Date(Date.now() + offsetMs);
  return {
    id,
    flightNumber: 'SB1',
    originAirportCode: 'LHR',
    destinationAirportCode: 'DXB',
    departureTime: dep.toISOString().slice(0, 19),
    arrivalTime: new Date(dep.getTime() + 6 * HOUR).toISOString().slice(0, 19),
    status: 'SCHEDULED',
  } as unknown as Flight;
}

function booking(status: string, checkIns: Array<string | undefined>): Booking {
  return {
    id: 1,
    bookingReference: 'SBTEST',
    flightId: 1,
    bookingStatus: status,
    bookingDate: '2026-01-01T00:00',
    totalFare: 100,
    ownerSubject: 'x',
    passengers: checkIns.map((ci, i) => ({
      id: i + 1,
      passengerId: i + 1,
      segmentIndex: 0,
      flightId: 1,
      firstName: 'P',
      lastName: String(i),
      travelClass: 'ECONOMY',
      fareType: 'FLEXI',
      cancelled: false,
      checkInStatus: ci,
    })),
  } as unknown as Booking;
}

describe('groupOf', () => {
  it('cancelled bookings are Cancelled whatever else happened', () => {
    expect(groupOf(booking('CANCELLED', ['CHECKED_IN']), [flightAt(-HOUR)])).toBe('cancelled');
  });

  it('upcoming with nobody checked in is Pending check-in', () => {
    expect(groupOf(booking('CONFIRMED', ['NOT_OPEN', 'OPEN']), [flightAt(48 * HOUR)])).toBe('pendingCheckIn');
  });

  it('upcoming with a boarding pass issued is Checked in', () => {
    expect(groupOf(booking('CONFIRMED', ['CHECKED_IN', 'OPEN']), [flightAt(5 * HOUR)])).toBe('checkedIn');
  });

  it('departed with a traveller who checked in is a Completed trip', () => {
    expect(groupOf(booking('CONFIRMED', ['CHECKED_IN']), [flightAt(-3 * HOUR)])).toBe('completed');
  });

  it('departed with nobody checked in is a No show', () => {
    expect(groupOf(booking('CONFIRMED', ['NO_SHOW']), [flightAt(-3 * HOUR)])).toBe('noShow');
    expect(groupOf(booking('CONFIRMED', ['OPEN']), [flightAt(-3 * HOUR)])).toBe('noShow');
  });

  it('a round trip stays upcoming until its LAST leg departs', () => {
    // Outbound flew 3h ago, return leaves tomorrow: still an upcoming trip.
    expect(
      groupOf(booking('CONFIRMED', ['OPEN', 'OPEN']), [flightAt(-3 * HOUR, 1), flightAt(24 * HOUR, 2)]),
    ).toBe('pendingCheckIn');
  });

  it('COMPLETED status trumps missing flight data', () => {
    expect(groupOf(booking('COMPLETED', ['CHECKED_IN']), [])).toBe('completed');
  });
});
