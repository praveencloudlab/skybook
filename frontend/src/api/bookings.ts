import { api } from './client';
import type { FareType, TravelClass } from './quotes';

/**
 * Bookings (FRONTEND_MODULE.md §5 screens 5-8).
 *
 * <p>Creating a booking is the journey's first WRITE, and the point where the
 * platform stops being synchronous: the response comes back CREATED/DRAFT, and a
 * payment row then appears over Kafka. Nothing here waits for that - the waiting
 * is the caller's job, via usePolledResource.
 */

export type BookingStatus =
  | 'DRAFT'
  | 'CREATED'
  | 'CONFIRMED'
  | 'PARTIALLY_CANCELLED'
  | 'CANCELLED'
  | 'COMPLETED';

export type PassengerType = 'ADULT' | 'CHILD' | 'INFANT';

export interface PassengerDetail {
  title?: string;
  firstName: string;
  middleName?: string;
  lastName: string;
  /** ISO date, e.g. 1990-01-31. */
  dob: string;
  gender?: string;
  /** ISO-3166 alpha-3, e.g. GBR. The server enforces exactly 3 characters. */
  nationality: string;
  passportNumber: string;
  passportExpiry: string;
  email?: string;
  phone?: string;
  travelClass: TravelClass;
  fareType: FareType;
  /** Omit to have a seat assigned free of charge. */
  seatNumber?: string;
  /** Round trip: the seat picked for the RETURN leg (omit = free auto-assign). */
  returnSeatNumber?: string;
  /** Extra checked bags to buy (ancillary), charged per bag. */
  extraBags?: number;
}

export interface BookingContact {
  contactName: string;
  contactEmail: string;
  contactPhone?: string;
}

export interface CreateBookingRequest {
  flightId: number;
  /**
   * Present = single-PNR round trip: the return leg books as segment 1 of
   * the SAME booking - one reference, one payment, one confirmation.
   */
  returnFlightId?: number;
  passengers: PassengerDetail[];
  contact: BookingContact;
  remarks?: string;
  /** Optional since V6 - ownership comes from the token, not from this. */
  customerId?: number;
}

/** One flight leg of the journey. status is server-derived (never stored). */
export interface BookingSegment {
  id: number;
  segmentIndex: number;
  flightId: number;
  status: 'UPCOMING' | 'CHECKED_IN' | 'CANCELLED';
}

/** One coupon = the traveller's right to fly ONE segment. */
export interface TicketCoupon {
  couponNumber: number;
  segmentIndex: number;
  bookingPassengerId: number;
  status: 'OPEN' | 'CHECKED_IN' | 'FLOWN' | 'CANCELLED' | 'REFUNDED';
}

/** IATA-style e-ticket: one per traveller, a coupon per segment. */
export interface Ticket {
  id: number;
  /** Raw 13 digits - display as 125-XXXXXXXXXX. */
  ticketNumber: string;
  passengerId: number;
  status: 'ISSUED' | 'VOID' | 'REFUNDED';
  issuedAt: string;
  coupons: TicketCoupon[];
}

export interface BookingPassenger {
  id: number;
  /** The traveller's identity id - shared by their rows on every segment. */
  passengerId?: number;
  /** 0 = outbound, 1 = return - a traveller has one row PER SEGMENT. */
  segmentIndex?: number;
  /** This row's own flight (differs per segment on a round trip). */
  flightId?: number;
  firstName: string;
  lastName: string;
  passportNumber?: string;
  /** Full identity snapshot - lets the UI rebook this passenger without retyping. */
  title?: string;
  gender?: string;
  dob?: string;
  nationality?: string;
  passportExpiry?: string;
  seatNumber: string | null;
  travelClass: TravelClass;
  fareType: FareType;
  baseFare?: string | number;
  seatSurcharge?: string | number;
  fare?: string | number;
  /** Ancillary bags bought at booking, and what they cost. */
  extraBags?: number;
  baggageFee?: string | number;
  /** This traveller has been cancelled off the booking (booking survives). */
  cancelled?: boolean;
  /** ADULT / CHILD / INFANT, from DOB - drives the guardian rule. */
  passengerType?: PassengerType;
  checkInStatus?: string;
}

/** Result of cancelling selected passengers. */
export interface CancelPassengersResult {
  booking: Booking;
  refundAmount: string | number;
  bookingCancelled: boolean;
}

export interface Booking {
  id: number;
  bookingReference: string;
  flightId: number;
  bookingStatus: BookingStatus;
  bookingDate: string;
  totalFare: string | number;
  ownerSubject: string | null;
  /** The journey's legs in order (single-PNR round trips have two). */
  segments?: BookingSegment[];
  passengers: BookingPassenger[];
  contact?: BookingContact;
  /** E-tickets, issued once the booking is CONFIRMED. */
  tickets?: Ticket[];
}

export const bookingsApi = {
  mine(signal?: AbortSignal): Promise<Booking[]> {
    return api.get<Booking[]>('/api/bookings/mine', { signal });
  },

  create(request: CreateBookingRequest, signal?: AbortSignal): Promise<Booking> {
    return api.post<Booking>('/api/bookings', request, { signal });
  },

  byId(id: number, signal?: AbortSignal): Promise<Booking> {
    return api.get<Booking>(`/api/bookings/${id}`, { signal });
  },

  byReference(pnr: string, signal?: AbortSignal): Promise<Booking> {
    return api.get<Booking>(`/api/bookings/reference/${pnr}`, { signal });
  },

  cancel(id: number, signal?: AbortSignal): Promise<Booking> {
    return api.patch<Booking>(`/api/bookings/${id}/cancel`, undefined, { signal });
  },

  /**
   * Cancel selected passengers off a booking. If they cover everyone the whole
   * booking is cancelled; otherwise it becomes PARTIALLY_CANCELLED and the rest
   * travel on. The server enforces the guardian rule (a minor can't be left
   * without an adult) and returns the refund for the cancelled passengers.
   */
  cancelPassengers(
    id: number,
    bookingPassengerIds: number[],
    signal?: AbortSignal,
  ): Promise<CancelPassengersResult> {
    return api.post<CancelPassengersResult>(
      `/api/bookings/${id}/passengers/cancel`,
      { bookingPassengerIds },
      { signal },
    );
  },

  /**
   * Drop just the return leg (segmentIndex >= 1 only - the outbound can't be
   * cancelled alone). Seats release, coupons refund, the booking survives as
   * PARTIALLY_CANCELLED with the outbound intact.
   */
  cancelSegment(id: number, segmentIndex: number, signal?: AbortSignal): Promise<CancelPassengersResult> {
    return api.post<CancelPassengersResult>(
      `/api/bookings/${id}/segments/${segmentIndex}/cancel`,
      undefined,
      { signal },
    );
  },

  /**
   * Premium date change: move one leg onto a new flight, SAME booking and
   * tickets - fare difference only. The server rejects non-Premium fares.
   */
  rebookSegment(id: number, segmentIndex: number, newFlightId: number, signal?: AbortSignal): Promise<Booking> {
    return api.post<Booking>(
      `/api/bookings/${id}/segments/${segmentIndex}/rebook`,
      { newFlightId },
      { signal },
    );
  },
};

/** ISO-3166 alpha-3 codes for the nationality field, kept short and common. */
export const NATIONALITIES = [
  'GBR', 'USA', 'IND', 'IRL', 'FRA', 'DEU', 'ESP', 'ITA', 'NLD', 'PRT',
  'ARE', 'QAT', 'SAU', 'ZAF', 'KEN', 'AUS', 'NZL', 'CAN', 'SGP', 'JPN',
] as const;

