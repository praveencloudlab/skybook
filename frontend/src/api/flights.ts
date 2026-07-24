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
  status: FlightStatus;
}

export interface SearchCriteria {
  origin: string;
  destination: string;
  date: string; // yyyy-MM-dd
}

export const flightsApi = {
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
};

/**
 * Routes offered up front (§10.4).
 *
 * The seed holds ~11,000 flights across 30 routes. A first-time visitor who does
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
export const AIRPORTS: Array<{ code: string; city: string }> = [
  { code: 'LHR', city: 'London Heathrow' },
  { code: 'MAN', city: 'Manchester' },
  { code: 'EDI', city: 'Edinburgh' },
  { code: 'GLA', city: 'Glasgow' },
  { code: 'BHX', city: 'Birmingham' },
  { code: 'JFK', city: 'New York JFK' },
  { code: 'ATL', city: 'Atlanta' },
  { code: 'DXB', city: 'Dubai' },
  { code: 'DOH', city: 'Doha' },
  { code: 'AUH', city: 'Abu Dhabi' },
  { code: 'DEL', city: 'Delhi' },
  { code: 'BOM', city: 'Mumbai' },
  { code: 'HKG', city: 'Hong Kong' },
  { code: 'JNB', city: 'Johannesburg' },
  { code: 'NBO', city: 'Nairobi' },
  { code: 'CDG', city: 'Paris' },
];
