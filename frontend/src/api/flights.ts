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
  { code: 'LGW', city: 'London Gatwick' },
  { code: 'STN', city: 'London Stansted' },
  { code: 'LTN', city: 'London Luton' },
  { code: 'LCY', city: 'London City' },
  { code: 'SEN', city: 'London Southend' },
  { code: 'LPL', city: 'Liverpool' },
  { code: 'EMA', city: 'East Midlands' },
  { code: 'NWI', city: 'Norwich' },
  { code: 'LBA', city: 'Leeds Bradford' },
  { code: 'NCL', city: 'Newcastle' },
  { code: 'MME', city: 'Teesside' },
  { code: 'BOH', city: 'Bournemouth' },
  { code: 'SOU', city: 'Southampton' },
  { code: 'EXT', city: 'Exeter' },
  { code: 'BRS', city: 'Bristol' },
  { code: 'CWL', city: 'Cardiff' },
  { code: 'NQY', city: 'Newquay' },
  { code: 'LEQ', city: 'Land\'s End' },
  { code: 'ISC', city: 'Isles of Scilly' },
  { code: 'ABZ', city: 'Aberdeen' },
  { code: 'INV', city: 'Inverness' },
  { code: 'DND', city: 'Dundee' },
  { code: 'KOI', city: 'Kirkwall' },
  { code: 'LSI', city: 'Shetland' },
  { code: 'SYY', city: 'Stornoway' },
  { code: 'BEB', city: 'Benbecula' },
  { code: 'BRR', city: 'Barra' },
  { code: 'TRE', city: 'Tiree' },
  { code: 'ILY', city: 'Islay' },
  { code: 'CAL', city: 'Campbeltown' },
  { code: 'PPW', city: 'Papa Westray' },
  { code: 'NRL', city: 'North Ronaldsay' },
  { code: 'NDY', city: 'Sanday' },
  { code: 'WRY', city: 'Westray' },
  { code: 'SOY', city: 'Stronsay' },
  { code: 'BFS', city: 'Belfast Intl' },
  { code: 'BHD', city: 'Belfast City' },
  { code: 'LDY', city: 'Derry' },
  { code: 'JER', city: 'Jersey' },
  { code: 'GCI', city: 'Guernsey' },
  { code: 'ACI', city: 'Alderney' },
  { code: 'IOM', city: 'Isle of Man' },
  { code: 'AMD', city: 'Ahmedabad' },
  { code: 'PNQ', city: 'Pune' },
  { code: 'GOI', city: 'Goa Dabolim' },
  { code: 'GOX', city: 'Goa Mopa' },
  { code: 'COK', city: 'Kochi' },
  { code: 'TRV', city: 'Thiruvananthapuram' },
  { code: 'NAG', city: 'Nagpur' },
  { code: 'JAI', city: 'Jaipur' },
  { code: 'LKO', city: 'Lucknow' },
  { code: 'IXC', city: 'Chandigarh' },
  { code: 'ATQ', city: 'Amritsar' },
  { code: 'GAU', city: 'Guwahati' },
  { code: 'BBI', city: 'Bhubaneswar' },
  { code: 'PAT', city: 'Patna' },
  { code: 'IDR', city: 'Indore' },
  { code: 'BHO', city: 'Bhopal' },
  { code: 'RPR', city: 'Raipur' },
  { code: 'VNS', city: 'Varanasi' },
  { code: 'SXR', city: 'Srinagar' },
  { code: 'IXB', city: 'Siliguri' },
  { code: 'CJB', city: 'Coimbatore' },
  { code: 'CCJ', city: 'Kozhikode' },
  { code: 'IXE', city: 'Mangaluru' },
  { code: 'IXZ', city: 'Port Blair' },
  { code: 'IXR', city: 'Ranchi' },
  { code: 'DED', city: 'Dehradun' },
  { code: 'UDR', city: 'Udaipur' },
  { code: 'JDH', city: 'Jodhpur' },
  { code: 'STV', city: 'Surat' },
  { code: 'CNN', city: 'Kannur' },
  { code: 'IXM', city: 'Madurai' },
  { code: 'TRZ', city: 'Tiruchirappalli' },
  { code: 'TIR', city: 'Tirupati' },
  { code: 'VGA', city: 'Vijayawada' },
  { code: 'RJA', city: 'Rajahmundry' },
  { code: 'IXU', city: 'Aurangabad' },
  { code: 'JLR', city: 'Jabalpur' },
  { code: 'JRG', city: 'Jharsuguda' },
  { code: 'GAY', city: 'Gaya' },
  { code: 'IXD', city: 'Prayagraj' },
  { code: 'GWL', city: 'Gwalior' },
  { code: 'JGA', city: 'Jamnagar' },
  { code: 'HSR', city: 'Rajkot' },
  { code: 'BHJ', city: 'Bhuj' },
  { code: 'BDQ', city: 'Vadodara' },
  { code: 'KNU', city: 'Kanpur' },
  { code: 'GOP', city: 'Gorakhpur' },
  { code: 'DBR', city: 'Darbhanga' },
  { code: 'IXJ', city: 'Jammu' },
  { code: 'IXL', city: 'Leh' },
  { code: 'DHM', city: 'Dharamshala' },
  { code: 'KUU', city: 'Kullu' },
  { code: 'IMF', city: 'Imphal' },
  { code: 'DIB', city: 'Dibrugarh' },
  { code: 'JRH', city: 'Jorhat' },
  { code: 'SHL', city: 'Shillong' },
  { code: 'AJL', city: 'Aizawl' },
  { code: 'DMU', city: 'Dimapur' },
  { code: 'IXA', city: 'Agartala' },
  { code: 'IXS', city: 'Silchar' },
  { code: 'HBX', city: 'Hubballi' },
  { code: 'MYQ', city: 'Mysuru' },
  { code: 'PGH', city: 'Pantnagar' },
  { code: 'TEZ', city: 'Tezpur' },
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
