import { api } from './client';
import type { Flight, FlightStatus, SearchCriteria } from './flights';
import type { Booking, BookingStatus } from './bookings';
import type { CheckIn } from './checkin';

/**
 * Admin surface (FRONTEND_MODULE.md Module 16).
 *
 * <p>The complete back-office: flight lifecycle (create, status, delay,
 * reschedule, cancel, delete), the booking desk (list/search/confirm/
 * complete/cancel), payments and refunds, gate operations (per-flight
 * check-ins, gate assignment, manifests, boarding-pass verification) and
 * fleet/inventory control. Every call maps to a real ADMIN-authorised
 * endpoint - the server is the gate, this client is the convenient face.
 */

export interface Aircraft {
  id: number;
  registrationNumber: string;
  manufacturer: string;
  model: string;
  totalSeats: number;
  status?: string;
}

export interface AircraftSeatRow {
  id?: number;
  seatNumber: string;
  rowNumber?: number;
  /** BUSINESS / PREMIUM_ECONOMY / ECONOMY / FIRST - inventory calls it seatType. */
  seatType: string;
  position?: string;
  status: string;
  exitRow?: boolean;
  listedSurcharge?: string | number | null;
}

/** GET /api/aircraft/{id}/seat-map: the aircraft header with its seats nested. */
export interface SeatMapResponse {
  aircraftId: number;
  registrationNumber: string;
  model: string;
  aircraftStatus: string;
  totalSeats: number;
  seats: AircraftSeatRow[];
}

export interface BookingSearch {
  bookingReference?: string;
  bookingStatus?: BookingStatus;
  passengerName?: string;
  email?: string;
}

export interface AdminPayment {
  id: number;
  bookingId: number;
  paymentReference?: string | null;
  paymentStatus: string;
  amount: string | number;
  currency: string;
  paymentMethod?: string | null;
  createdAt?: string;
}

export interface Refund {
  id: number;
  paymentId?: number | null;
  bookingId?: number | null;
  amount: string | number;
  currency: string;
  status?: string;
  reason?: string | null;
  createdAt?: string;
  [key: string]: unknown;
}

export interface Manifest {
  id?: number;
  flightId: number;
  checkedInCount: number;
  boardedCount: number;
  noShowCount: number;
  baggageCount: number;
  baggageWeightKg?: string | number | null;
  status: string;
  finalizedAt?: string | null;
  [key: string]: unknown;
}

/**
 * Success body of GET /api/boarding-passes/verify: the pass details, flat.
 * A bad pass never reaches this shape - the server answers with an error
 * (tampered / unknown / revoked / already boarded / not checked in), which
 * surfaces as an ApiError carrying the reason.
 */
export interface BoardingPassVerification {
  passengerName: string;
  bookingReference: string;
  flightNumber: string;
  seatNumber: string;
  gate?: string | null;
  boardingGroup?: string | null;
}

export interface FlightInventorySummary {
  flightId?: number;
  status?: string;
  [key: string]: unknown;
}

export interface CreateFlightInput {
  flightNumber: string;
  airlineCode: string;
  originAirportCode: string;
  destinationAirportCode: string;
  departureTime: string;
  arrivalTime: string;
}

export const adminApi = {
  /* ---- flights: full lifecycle ---- */
  searchFlights({ origin, destination, date }: SearchCriteria, signal?: AbortSignal): Promise<Flight[]> {
    const q = new URLSearchParams({
      originAirportCode: origin,
      destinationAirportCode: destination,
      departureDate: date,
    });
    return api.get<Flight[]>(`/api/flights/search?${q}`, { signal });
  },
  flightsByStatus(status: FlightStatus, signal?: AbortSignal): Promise<Flight[]> {
    return api.get<Flight[]>(`/api/flights/status/${status}`, { signal });
  },
  createFlight(input: CreateFlightInput): Promise<Flight> {
    return api.post<Flight>('/api/flights', input);
  },
  cancelFlight(id: number): Promise<Flight> {
    return api.patch<Flight>(`/api/flights/${id}/cancel`);
  },
  setFlightStatus(id: number, status: FlightStatus): Promise<Flight> {
    return api.patch<Flight>(`/api/flights/${id}/status`, { status });
  },
  delayFlight(id: number, newDepartureTime: string, newArrivalTime: string, reason: string): Promise<Flight> {
    return api.patch<Flight>(`/api/flights/${id}/delay`, { newDepartureTime, newArrivalTime, reason });
  },
  rescheduleFlight(id: number, departureTime: string, arrivalTime: string, remarks?: string): Promise<Flight> {
    return api.patch<Flight>(`/api/flights/${id}/reschedule`, { departureTime, arrivalTime, remarks });
  },
  deleteFlight(id: number): Promise<void> {
    return api.delete<void>(`/api/flights/${id}`);
  },

  /* ---- bookings: the back-office desk ---- */
  allBookings(signal?: AbortSignal): Promise<Booking[]> {
    return api.get<Booking[]>('/api/bookings', { signal });
  },
  searchBookings(params: BookingSearch, signal?: AbortSignal): Promise<Booking[]> {
    const q = new URLSearchParams();
    for (const [k, v] of Object.entries(params)) {
      if (v) q.set(k, String(v));
    }
    return api.get<Booking[]>(`/api/bookings/search?${q}`, { signal });
  },
  confirmBooking(id: number): Promise<Booking> {
    return api.patch<Booking>(`/api/bookings/${id}/confirm`);
  },
  completeBooking(id: number): Promise<Booking> {
    return api.patch<Booking>(`/api/bookings/${id}/complete`);
  },
  cancelBooking(id: number, reason: string): Promise<Booking> {
    return api.patch<Booking>(`/api/bookings/${id}/cancel`, { reason });
  },

  /* ---- payments, refunds ---- */
  paymentForBooking(bookingId: number, signal?: AbortSignal): Promise<AdminPayment | null> {
    return api.get<AdminPayment | null>(`/api/payments/booking/${bookingId}`, { signal });
  },
  refundPayment(id: number): Promise<AdminPayment> {
    return api.patch<AdminPayment>(`/api/payments/${id}/refund`);
  },
  cancelPayment(id: number): Promise<AdminPayment> {
    return api.patch<AdminPayment>(`/api/payments/${id}/cancel`);
  },
  refunds(signal?: AbortSignal): Promise<Refund[]> {
    return api.get<Refund[]>('/api/refunds', { signal });
  },

  /* ---- gate operations ---- */
  checkInsForFlight(flightId: number, signal?: AbortSignal): Promise<CheckIn[]> {
    return api.get<CheckIn[]>(`/api/checkins/flight/${flightId}`, { signal });
  },
  assignGate(checkInId: number, gate: string): Promise<CheckIn> {
    return api.patch<CheckIn>(`/api/checkins/${checkInId}/gate`, { gate });
  },
  manifest(flightId: number, signal?: AbortSignal): Promise<Manifest> {
    return api.get<Manifest>(`/api/manifests/${flightId}`, { signal });
  },
  finalizeManifest(flightId: number): Promise<Manifest> {
    return api.post<Manifest>(`/api/manifests/${flightId}/finalize`);
  },
  verifyBoardingPass(token: string, signal?: AbortSignal): Promise<BoardingPassVerification> {
    return api.get<BoardingPassVerification>(
      `/api/boarding-passes/verify?token=${encodeURIComponent(token)}`, { signal });
  },

  /** Create a flight's seat inventory on a chosen aircraft's cabin layout. */
  createInventory(flightId: number, aircraftId: number): Promise<FlightInventorySummary> {
    return api.post<FlightInventorySummary>('/api/inventory', { flightId, aircraftId, blockedSeats: 0 });
  },

  /* ---- fleet & inventory ---- */
  aircraft(signal?: AbortSignal): Promise<Aircraft[]> {
    return api.get<Aircraft[]>('/api/aircraft', { signal });
  },
  setAircraftStatus(id: number, status: string): Promise<Aircraft> {
    return api.patch<Aircraft>(`/api/aircraft/${id}/status`, { status });
  },
  seatMap(aircraftId: number, signal?: AbortSignal): Promise<SeatMapResponse> {
    return api.get<SeatMapResponse>(`/api/aircraft/${aircraftId}/seat-map`, { signal });
  },
  closeInventory(flightId: number): Promise<FlightInventorySummary> {
    return api.patch<FlightInventorySummary>(`/api/inventory/flight/${flightId}/close`);
  },
  reopenInventory(flightId: number): Promise<FlightInventorySummary> {
    return api.patch<FlightInventorySummary>(`/api/inventory/flight/${flightId}/reopen`);
  },
};
