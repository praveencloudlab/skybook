import { api } from './client';

/**
 * Fare quotes (FRONTEND_MODULE.md §5 screen 3).
 *
 * <p>Mirrors booking-service's QuoteResponse, including two distinctions that
 * are easy to flatten and wrong to flatten:
 *
 * <ul>
 *   <li>A cabin <b>absent</b> from the list means the aircraft does not sell it
 *       at all. That is not the same as "sold out", and must not be shown as
 *       "0 seats".</li>
 *   <li>{@code availableSeats === null} means the flight has no seat-inventory
 *       record: fares are still quotable, availability is simply unknown.
 *       Rendering null as 0 would tell a passenger a flight is full when nobody
 *       said that.</li>
 * </ul>
 */

export type TravelClass = 'ECONOMY' | 'PREMIUM_ECONOMY' | 'BUSINESS' | 'FIRST';
export type FareType = 'SAVER' | 'FLEXI' | 'PREMIUM';

export const TRAVEL_CLASS_LABELS: Record<TravelClass, string> = {
  ECONOMY: 'Economy',
  PREMIUM_ECONOMY: 'Premium Economy',
  BUSINESS: 'Business',
  FIRST: 'First',
};

/** Cheapest first - the order a fare table is read. */
export const TRAVEL_CLASS_ORDER: TravelClass[] = [
  'ECONOMY',
  'PREMIUM_ECONOMY',
  'BUSINESS',
  'FIRST',
];

export const FARE_TYPE_ORDER: FareType[] = ['SAVER', 'FLEXI', 'PREMIUM'];

export const FARE_TYPE_LABELS: Record<FareType, string> = {
  SAVER: 'Saver',
  FLEXI: 'Flexi',
  PREMIUM: 'Premium',
};

/** What each fare actually buys - shown so the price difference is explicable. */
export const FARE_TYPE_BLURB: Record<FareType, string> = {
  SAVER: 'Lowest fare. Cancellation fees apply.',
  FLEXI: 'More generous refund on cancellation.',
  PREMIUM: 'Most flexible, highest refund.',
};

export interface CabinQuote {
  travelClass: TravelClass;
  /** null = no inventory record, i.e. availability unknown (NOT sold out). */
  availableSeats: number | null;
  baseFares: Partial<Record<FareType, string | number>>;
  fromFare: string | number;
}

export interface Quote {
  flightId: number;
  currency: string;
  cabins: CabinQuote[];
}

/** One day of the fare calendar: bookable departures + the cabin's cheapest fare. */
export interface FareCalendarDay {
  date: string; // yyyy-MM-dd
  flights: number;
  minFare: string | number;
  currency: string;
}

export const quotesApi = {
  forFlight(flightId: number, signal?: AbortSignal): Promise<Quote> {
    return api.post<Quote>('/api/bookings/quote', { flightId }, { signal });
  },

  /**
   * Per-date lowest fares for a route and cabin - the SAME pricing formula
   * every quote and checkout uses, so the calendar never disagrees with what
   * a booking actually costs.
   */
  fareCalendar(
    origin: string,
    destination: string,
    startDate: string,
    endDate: string,
    travelClass: TravelClass,
    signal?: AbortSignal,
  ): Promise<FareCalendarDay[]> {
    const query = new URLSearchParams({
      originAirportCode: origin,
      destinationAirportCode: destination,
      startDate,
      endDate,
      travelClass,
    });
    return api.get<FareCalendarDay[]>(`/api/bookings/fare-calendar?${query}`, { signal });
  },
};
