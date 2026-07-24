import { api } from './client';

/**
 * Check-in and boarding passes (FRONTEND_MODULE.md §5 screens 9-10).
 *
 * <p>Check-in records are created for us: booking-service publishes a CONFIRMED
 * event, checkin-service consumes it and creates one record PER PASSENGER. So
 * after confirmation there is a third asynchronous wait before check-in is even
 * possible.
 *
 * <p><b>The journey ends at the boarding pass.</b> Boarding is a gate operation
 * and the platform correctly refuses a passenger who tries it (403), so no
 * "board" action is exposed here - offering a button that always fails would be
 * worse than not offering it.
 */

/**
 * The server's own view of where a passenger is in the check-in lifecycle.
 *
 * <p>This is <b>authoritative</b>, and the UI must gate on it rather than
 * recomputing the window from departure time. The window is server
 * configuration - the e2e profile deliberately widens it - so a client that
 * derives "too early" from a hardcoded 24 hours will disable a button the server
 * would have accepted. Ask, do not guess.
 */
export type CheckInStatus =
  | 'NOT_OPEN'
  | 'OPEN'
  | 'CHECKED_IN'
  | 'BOARDED'
  | 'COMPLETED'
  | 'NO_SHOW'
  | 'CANCELLED';

export interface CheckIn {
  id: number;
  bookingId: number;
  bookingReference: string;
  bookingPassengerId: number;
  flightId: number;
  flightNumber: string;
  originAirportCode: string;
  destinationAirportCode: string;
  departureTime: string;
  passengerName: string;
  seatNumber: string | null;
  travelClass: string;
  status: CheckInStatus;
}

export interface BoardingPass {
  id: number;
  boardingPassNumber: string;
  checkInId: number;
  passengerName: string;
  flightNumber: string;
  seatNumber: string;
  gate?: string | null;
  boardingGroup?: string | null;
  boardingTime?: string | null;
  /** Signed token, rendered as the scannable code. */
  barcodeToken?: string;
}

export const checkinApi = {
  /** Check-in records for a booking - one per passenger. */
  forBooking(bookingId: number, signal?: AbortSignal): Promise<CheckIn[]> {
    return api.get<CheckIn[]>(`/api/checkins/booking/${bookingId}`, { signal });
  },

  /**
   * Check a passenger in.
   *
   * <p>Answers 409 outside the window (opens 24h before departure, closes 45
   * minutes before). That is a normal, explainable outcome rather than a fault,
   * and the server's message says exactly when the window opens - which is why
   * the UI prefers it to anything it could invent.
   */
  checkIn(checkInId: number, signal?: AbortSignal): Promise<CheckIn> {
    return api.patch<CheckIn>(`/api/checkins/${checkInId}/checkin`, undefined, { signal });
  },

  boardingPass(checkInId: number, signal?: AbortSignal): Promise<BoardingPass> {
    return api.get<BoardingPass>(`/api/boarding-passes/checkin/${checkInId}`, { signal });
  },
};

/** When check-in opens, given a departure time (24h before). */
export function checkInOpensAt(departureTime: string): Date {
  return new Date(new Date(departureTime).getTime() - 24 * 60 * 60 * 1000);
}

/** When check-in closes (45 minutes before departure). */
export function checkInClosesAt(departureTime: string): Date {
  return new Date(new Date(departureTime).getTime() - 45 * 60 * 1000);
}
