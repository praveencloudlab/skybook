import { api } from './client';

/**
 * Flight search (FRONTEND_MODULE.md §5, screen 2).
 *
 * Mirrors flight-service's contract exactly: `/search` wants an origin, a
 * destination and ONE date - it is not a flexible-date search, so the UI must
 * not imply otherwise.
 */

export type FlightStatus =
  | 'SCHEDULED'
  | 'DELAYED'
  | 'CANCELLED'
  | 'BOARDING'
  | 'DEPARTED'
  | 'ARRIVED';

export interface Flight {
  id: number;
  flightNumber: string;
  airlineCode: string;
  originAirportCode: string;
  destinationAirportCode: string;
  departureTime: string;
  arrivalTime: string;
  /**
   * Minutes in the air, computed server-side. The two times above are wall
   * clocks at DIFFERENT airports, so subtracting them here would measure the
   * flight plus the offset between the zones; only the server knows the zones.
   */
  durationMinutes: number;
  /** The carrier's real terminal at each end (server TerminalPolicy); absent on old rows. */
  departureTerminal?: string | null;
  arrivalTerminal?: string | null;
  status: FlightStatus;
}

export interface SearchCriteria {
  origin: string;
  destination: string;
  date: string; // yyyy-MM-dd
}

/** One day of a route's availability calendar - days with no flights are absent. */
export interface RouteCalendarDay {
  date: string; // yyyy-MM-dd
  flights: number;
}

/** One bookable trip option: a direct leg or a 1-2 stop self-transfer connection. */
export interface Itinerary {
  legs: Flight[];
  stops: number;
  totalDurationMinutes: number;
  /** Waiting time at each stop, minutes, in leg order. */
  layoverMinutes: number[];
  /**
   * All legs on one carrier: sold as a single through-ticket booking (bags
   * checked through, protected connection). False = self-transfer, one
   * ticket per leg.
   */
  sameCarrier: boolean;
}

export const flightsApi = {
  itineraries({ origin, destination, date }: SearchCriteria, signal?: AbortSignal): Promise<Itinerary[]> {
    const query = new URLSearchParams({
      originAirportCode: origin,
      destinationAirportCode: destination,
      departureDate: date,
    });
    return api.get<Itinerary[]>(`/api/flights/itineraries?${query}`, { signal });
  },

  search({ origin, destination, date }: SearchCriteria, signal?: AbortSignal): Promise<Flight[]> {
    const query = new URLSearchParams({
      originAirportCode: origin,
      destinationAirportCode: destination,
      departureDate: date,
    });
    return api.get<Flight[]>(`/api/flights/search?${query}`, { signal });
  },

  byId(id: number, signal?: AbortSignal): Promise<Flight> {
    return api.get<Flight>(`/api/flights/${id}`, { signal });
  },

  /** Bookable-departure counts per day, for the fare-calendar date picker. */
  calendar(
    origin: string,
    destination: string,
    startDate: string,
    endDate: string,
    signal?: AbortSignal,
  ): Promise<RouteCalendarDay[]> {
    const query = new URLSearchParams({
      originAirportCode: origin,
      destinationAirportCode: destination,
      startDate,
      endDate,
    });
    return api.get<RouteCalendarDay[]>(`/api/flights/calendar?${query}`, { signal });
  },
};

/**
 * Routes offered up front (§10.4).
 *
 * The seed serves 29 airports - every pair, three times daily, plus curated flagship routes. A first-time visitor who does
 * not know which of them exist would either face an empty result (and reasonably
 * conclude the thing is broken) or an undifferentiated wall of departures.
 * Offering a handful of recognisable routes means the app demonstrates itself;
 * full search remains available for anything else.
 *
 * These mirror rows in scripts/seed/routes.json, so they always return results.
 */
export const POPULAR_ROUTES: Array<{ origin: string; destination: string; label: string }> = [
  { origin: 'LHR', destination: 'JFK', label: 'London → New York' },
  { origin: 'LHR', destination: 'DXB', label: 'London → Dubai' },
  { origin: 'LHR', destination: 'DEL', label: 'London → Delhi' },
  { origin: 'LHR', destination: 'CDG', label: 'London → Paris' },
  { origin: 'MAN', destination: 'DXB', label: 'Manchester → Dubai' },
  { origin: 'EDI', destination: 'DOH', label: 'Edinburgh → Doha' },
];

/** Airports the seed actually serves, for the origin/destination pickers. */
/** Airline display names by IATA code - mirrors notification-service's AirlineLookup. */
export const AIRLINE_NAMES: Record<string, string> = {
  SB: 'SkyBook Airways',
  AF: 'Air France',
  AI: 'Air India',
  BA: 'British Airways',
  CX: 'Cathay Pacific',
  EK: 'Emirates',
  EY: 'Etihad Airways',
  LH: 'Lufthansa',
  QF: 'Qantas',
  QR: 'Qatar Airways',
  SQ: 'Singapore Airlines',
  TK: 'Turkish Airlines',
  VS: 'Virgin Atlantic',
  '6E': 'IndiGo',
  IX: 'Air India Express',
};

export function airlineNameFor(codeOrFlightNumber: string | undefined): string {
  if (!codeOrFlightNumber) return 'SkyBook Airways';
  const code = /^\d/.test(codeOrFlightNumber.charAt(1) ?? '')
    ? codeOrFlightNumber.slice(0, 2)
    : codeOrFlightNumber.slice(0, 2);
  return AIRLINE_NAMES[code] ?? AIRLINE_NAMES[codeOrFlightNumber] ?? 'SkyBook Airways';
}

export const AIRPORTS: Array<{ code: string; city: string }> = [
  { code: 'LHR', city: 'London Heathrow' },
  { code: 'MAN', city: 'Manchester' },
  { code: 'EDI', city: 'Edinburgh' },
  { code: 'GLA', city: 'Glasgow' },
  { code: 'BHX', city: 'Birmingham' },
  { code: 'JFK', city: 'New York JFK' },
  { code: 'ATL', city: 'Atlanta' },
  { code: 'LAX', city: 'Los Angeles' },
  { code: 'SFO', city: 'San Francisco' },
  { code: 'ORD', city: 'Chicago O\'Hare' },
  { code: 'DFW', city: 'Dallas-Fort Worth' },
  { code: 'MIA', city: 'Miami' },
  { code: 'HYD', city: 'Hyderabad' },
  { code: 'VTZ', city: 'Visakhapatnam' },
  { code: 'MAA', city: 'Chennai' },
  { code: 'BLR', city: 'Bengaluru' },
  { code: 'CCU', city: 'Kolkata' },
  { code: 'DXB', city: 'Dubai' },
  { code: 'DOH', city: 'Doha' },
  { code: 'AUH', city: 'Abu Dhabi' },
  { code: 'DEL', city: 'Delhi' },
  { code: 'BOM', city: 'Mumbai' },
  { code: 'HKG', city: 'Hong Kong' },
  { code: 'JNB', city: 'Johannesburg' },
  { code: 'NBO', city: 'Nairobi' },
  { code: 'CDG', city: 'Paris' },
  { code: 'FRA', city: 'Frankfurt' },
  { code: 'IST', city: 'Istanbul' },
  { code: 'SIN', city: 'Singapore' },
  { code: 'SYD', city: 'Sydney' },
];
