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

/** Proper airport name for documents; undefined when we only know the city. */
export function airportNameFor(code: string): string | undefined {
  return AIRPORTS.find((a) => a.code === code)?.name;
}

export function airlineNameFor(codeOrFlightNumber: string | undefined): string {
  if (!codeOrFlightNumber) return 'SkyBook Airways';
  const code = /^\d/.test(codeOrFlightNumber.charAt(1) ?? '')
    ? codeOrFlightNumber.slice(0, 2)
    : codeOrFlightNumber.slice(0, 2);
  return AIRLINE_NAMES[code] ?? AIRLINE_NAMES[codeOrFlightNumber] ?? 'SkyBook Airways';
}

export const AIRPORTS: Array<{ code: string; city: string; name?: string }> = [
  { code: 'LGW', city: 'London Gatwick', name: 'London Gatwick' },
  { code: 'STN', city: 'London Stansted', name: 'London Stansted' },
  { code: 'LTN', city: 'London Luton', name: 'London Luton' },
  { code: 'LCY', city: 'London City', name: 'London City' },
  { code: 'SEN', city: 'London Southend', name: 'London Southend' },
  { code: 'LPL', city: 'Liverpool', name: 'Liverpool John Lennon' },
  { code: 'EMA', city: 'East Midlands', name: 'East Midlands' },
  { code: 'NWI', city: 'Norwich', name: 'Norwich' },
  { code: 'LBA', city: 'Leeds Bradford', name: 'Leeds Bradford' },
  { code: 'NCL', city: 'Newcastle', name: 'Newcastle Intl' },
  { code: 'MME', city: 'Teesside', name: 'Teesside Intl' },
  { code: 'BOH', city: 'Bournemouth', name: 'Bournemouth' },
  { code: 'SOU', city: 'Southampton', name: 'Southampton' },
  { code: 'EXT', city: 'Exeter', name: 'Exeter' },
  { code: 'BRS', city: 'Bristol', name: 'Bristol' },
  { code: 'CWL', city: 'Cardiff', name: 'Cardiff' },
  { code: 'NQY', city: 'Newquay', name: 'Cornwall Airport Newquay' },
  { code: 'LEQ', city: 'Land\'s End', name: 'Land\'s End' },
  { code: 'ISC', city: 'Isles of Scilly', name: 'St Mary\'s' },
  { code: 'ABZ', city: 'Aberdeen', name: 'Aberdeen Intl' },
  { code: 'INV', city: 'Inverness', name: 'Inverness' },
  { code: 'DND', city: 'Dundee', name: 'Dundee' },
  { code: 'KOI', city: 'Kirkwall', name: 'Kirkwall' },
  { code: 'LSI', city: 'Shetland', name: 'Sumburgh' },
  { code: 'SYY', city: 'Stornoway', name: 'Stornoway' },
  { code: 'BEB', city: 'Benbecula', name: 'Benbecula' },
  { code: 'BRR', city: 'Barra', name: 'Barra' },
  { code: 'TRE', city: 'Tiree', name: 'Tiree' },
  { code: 'ILY', city: 'Islay', name: 'Islay' },
  { code: 'CAL', city: 'Campbeltown', name: 'Campbeltown' },
  { code: 'PPW', city: 'Papa Westray', name: 'Papa Westray' },
  { code: 'NRL', city: 'North Ronaldsay', name: 'North Ronaldsay' },
  { code: 'NDY', city: 'Sanday', name: 'Sanday' },
  { code: 'WRY', city: 'Westray', name: 'Westray' },
  { code: 'SOY', city: 'Stronsay', name: 'Stronsay' },
  { code: 'BFS', city: 'Belfast Intl', name: 'Belfast Intl' },
  { code: 'BHD', city: 'Belfast City', name: 'George Best Belfast City' },
  { code: 'LDY', city: 'Derry', name: 'City of Derry' },
  { code: 'JER', city: 'Jersey', name: 'Jersey' },
  { code: 'GCI', city: 'Guernsey', name: 'Guernsey' },
  { code: 'ACI', city: 'Alderney', name: 'Alderney' },
  { code: 'IOM', city: 'Isle of Man', name: 'Isle of Man (Ronaldsway)' },
  { code: 'AMD', city: 'Ahmedabad', name: 'Sardar Vallabhbhai Patel Intl' },
  { code: 'PNQ', city: 'Pune', name: 'Pune Airport' },
  { code: 'GOI', city: 'Goa Dabolim', name: 'Dabolim Airport' },
  { code: 'GOX', city: 'Goa Mopa', name: 'Manohar Intl' },
  { code: 'COK', city: 'Kochi', name: 'Cochin Intl' },
  { code: 'TRV', city: 'Thiruvananthapuram', name: 'Trivandrum Intl' },
  { code: 'NAG', city: 'Nagpur', name: 'Dr. Babasaheb Ambedkar Intl' },
  { code: 'JAI', city: 'Jaipur', name: 'Jaipur Intl' },
  { code: 'LKO', city: 'Lucknow', name: 'Chaudhary Charan Singh Intl' },
  { code: 'IXC', city: 'Chandigarh', name: 'Shaheed Bhagat Singh Intl' },
  { code: 'ATQ', city: 'Amritsar', name: 'Sri Guru Ram Dass Jee Intl' },
  { code: 'GAU', city: 'Guwahati', name: 'Lokpriya Gopinath Bordoloi Intl' },
  { code: 'BBI', city: 'Bhubaneswar', name: 'Biju Patnaik Intl' },
  { code: 'PAT', city: 'Patna', name: 'Jay Prakash Narayan Intl' },
  { code: 'IDR', city: 'Indore', name: 'Devi Ahilya Bai Holkar' },
  { code: 'BHO', city: 'Bhopal', name: 'Raja Bhoj' },
  { code: 'RPR', city: 'Raipur', name: 'Swami Vivekananda' },
  { code: 'VNS', city: 'Varanasi', name: 'Lal Bahadur Shastri Intl' },
  { code: 'SXR', city: 'Srinagar', name: 'Sheikh ul-Alam Intl' },
  { code: 'IXB', city: 'Siliguri', name: 'Bagdogra Airport' },
  { code: 'CJB', city: 'Coimbatore', name: 'Coimbatore Intl' },
  { code: 'CCJ', city: 'Kozhikode', name: 'Calicut Intl' },
  { code: 'IXE', city: 'Mangaluru', name: 'Mangaluru Intl' },
  { code: 'IXZ', city: 'Port Blair', name: 'Veer Savarkar Intl' },
  { code: 'IXR', city: 'Ranchi', name: 'Birsa Munda' },
  { code: 'DED', city: 'Dehradun', name: 'Jolly Grant' },
  { code: 'UDR', city: 'Udaipur', name: 'Maharana Pratap' },
  { code: 'JDH', city: 'Jodhpur', name: 'Jodhpur Airport' },
  { code: 'STV', city: 'Surat', name: 'Surat Intl' },
  { code: 'CNN', city: 'Kannur', name: 'Kannur Intl' },
  { code: 'IXM', city: 'Madurai', name: 'Madurai Airport' },
  { code: 'TRZ', city: 'Tiruchirappalli', name: 'Tiruchirappalli Intl' },
  { code: 'TIR', city: 'Tirupati', name: 'Tirupati Airport' },
  { code: 'VGA', city: 'Vijayawada', name: 'Vijayawada Intl' },
  { code: 'RJA', city: 'Rajahmundry', name: 'Rajahmundry Airport' },
  { code: 'IXU', city: 'Aurangabad', name: 'Aurangabad Airport' },
  { code: 'JLR', city: 'Jabalpur', name: 'Jabalpur Airport' },
  { code: 'JRG', city: 'Jharsuguda', name: 'Veer Surendra Sai' },
  { code: 'GAY', city: 'Gaya', name: 'Gaya Intl' },
  { code: 'IXD', city: 'Prayagraj', name: 'Prayagraj Airport' },
  { code: 'GWL', city: 'Gwalior', name: 'Rajmata Vijaya Raje Scindia' },
  { code: 'JGA', city: 'Jamnagar', name: 'Jamnagar Airport' },
  { code: 'HSR', city: 'Rajkot', name: 'Rajkot Intl (Hirasar)' },
  { code: 'BHJ', city: 'Bhuj', name: 'Bhuj Airport' },
  { code: 'BDQ', city: 'Vadodara', name: 'Vadodara Airport' },
  { code: 'KNU', city: 'Kanpur', name: 'Kanpur Airport' },
  { code: 'GOP', city: 'Gorakhpur', name: 'Gorakhpur Airport' },
  { code: 'DBR', city: 'Darbhanga', name: 'Darbhanga Airport' },
  { code: 'IXJ', city: 'Jammu', name: 'Jammu Airport' },
  { code: 'IXL', city: 'Leh', name: 'Kushok Bakula Rimpochee' },
  { code: 'DHM', city: 'Dharamshala', name: 'Kangra Airport' },
  { code: 'KUU', city: 'Kullu', name: 'Bhuntar Airport' },
  { code: 'IMF', city: 'Imphal', name: 'Bir Tikendrajit Intl' },
  { code: 'DIB', city: 'Dibrugarh', name: 'Dibrugarh Airport' },
  { code: 'JRH', city: 'Jorhat', name: 'Jorhat Airport' },
  { code: 'SHL', city: 'Shillong', name: 'Shillong Airport' },
  { code: 'AJL', city: 'Aizawl', name: 'Lengpui Airport' },
  { code: 'DMU', city: 'Dimapur', name: 'Dimapur Airport' },
  { code: 'IXA', city: 'Agartala', name: 'Maharaja Bir Bikram' },
  { code: 'IXS', city: 'Silchar', name: 'Silchar Airport' },
  { code: 'HBX', city: 'Hubballi', name: 'Hubballi Airport' },
  { code: 'MYQ', city: 'Mysuru', name: 'Mysuru Airport' },
  { code: 'PGH', city: 'Pantnagar', name: 'Pantnagar Airport' },
  { code: 'TEZ', city: 'Tezpur', name: 'Tezpur Airport' },
  { code: 'LHR', city: 'London Heathrow', name: 'London Heathrow' },
  { code: 'MAN', city: 'Manchester', name: 'Manchester' },
  { code: 'EDI', city: 'Edinburgh', name: 'Edinburgh' },
  { code: 'GLA', city: 'Glasgow', name: 'Glasgow' },
  { code: 'BHX', city: 'Birmingham', name: 'Birmingham' },
  { code: 'JFK', city: 'New York JFK', name: 'John F. Kennedy Intl' },
  { code: 'ATL', city: 'Atlanta', name: 'Hartsfield-Jackson Atlanta' },
  { code: 'LAX', city: 'Los Angeles', name: 'Los Angeles Intl' },
  { code: 'SFO', city: 'San Francisco', name: 'San Francisco Intl' },
  { code: 'ORD', city: 'Chicago O\'Hare', name: 'Chicago O\'Hare' },
  { code: 'DFW', city: 'Dallas-Fort Worth', name: 'Dallas Fort Worth Intl' },
  { code: 'MIA', city: 'Miami', name: 'Miami Intl' },
  { code: 'HYD', city: 'Hyderabad', name: 'Rajiv Gandhi Intl' },
  { code: 'VTZ', city: 'Visakhapatnam', name: 'Alluri Sitarama Raju Intl (Bhogapuram)' },
  { code: 'MAA', city: 'Chennai', name: 'Chennai Intl' },
  { code: 'BLR', city: 'Bengaluru', name: 'Kempegowda Intl' },
  { code: 'CCU', city: 'Kolkata', name: 'Netaji Subhas Chandra Bose Intl' },
  { code: 'DXB', city: 'Dubai', name: 'Dubai Intl' },
  { code: 'DOH', city: 'Doha', name: 'Hamad Intl' },
  { code: 'AUH', city: 'Abu Dhabi', name: 'Zayed Intl' },
  { code: 'DEL', city: 'Delhi', name: 'Indira Gandhi Intl' },
  { code: 'BOM', city: 'Mumbai', name: 'Chhatrapati Shivaji Maharaj Intl' },
  { code: 'HKG', city: 'Hong Kong', name: 'Hong Kong Intl' },
  { code: 'JNB', city: 'Johannesburg', name: 'O.R. Tambo Intl' },
  { code: 'NBO', city: 'Nairobi', name: 'Jomo Kenyatta Intl' },
  { code: 'CDG', city: 'Paris', name: 'Paris Charles de Gaulle' },
  { code: 'FRA', city: 'Frankfurt', name: 'Frankfurt Main' },
  { code: 'IST', city: 'Istanbul', name: 'Istanbul' },
  { code: 'SIN', city: 'Singapore', name: 'Singapore Changi' },
  { code: 'SYD', city: 'Sydney', name: 'Sydney Kingsford Smith' },
];
