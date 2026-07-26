import { api } from './client';
import type { Flight, FlightStatus, SearchCriteria } from './flights';
import type { Booking, BookingStatus } from './bookings';

/**
 * Admin surface (FRONTEND_MODULE.md Module 16).
 *
 * <p>Only endpoints that actually exist and are ADMIN-authorised: flight
 * operations, the booking back-office (list-all + search + confirm/complete),
 * and the read-only fleet. There is deliberately no users/reports/logs API here
 * because the backend exposes none - a screen with nothing behind it would be a
 * lie. Every call is gated ADMIN server-side; the UI guard is cosmetic.
 */

export interface Aircraft {
  id: number;
  registrationNumber: string;
  manufacturer: string;
  model: string;
  totalSeats: number;
  status?: string;
}

export interface BookingSearch {
  bookingReference?: string;
  bookingStatus?: BookingStatus;
  passengerName?: string;
  email?: string;
}

export const adminApi = {
  // --- Flights ---
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
  cancelFlight(id: number): Promise<Flight> {
    return api.patch<Flight>(`/api/flights/${id}/cancel`);
  },
  setFlightStatus(id: number, status: FlightStatus): Promise<Flight> {
    return api.patch<Flight>(`/api/flights/${id}/status`, { status });
  },

  // --- Bookings (back-office) ---
  allBookings(signal?: AbortSignal): Promise<Booking[]> {
    return api.get<Booking[]>('/api/bookings', { signal });
  },
  searchBookings(params: BookingSearch, signal?: AbortSignal): Promise<Booking[]> {
    const q = new URLSearchParams();
    for (const [k, v] of Object.entries(params)) {
      if (v) q.set(k, v);
    }
    return api.get<Booking[]>(`/api/bookings/search?${q}`, { signal });
  },
  confirmBooking(id: number): Promise<Booking> {
    return api.patch<Booking>(`/api/bookings/${id}/confirm`);
  },
  completeBooking(id: number): Promise<Booking> {
    return api.patch<Booking>(`/api/bookings/${id}/complete`);
  },

  // --- Fleet (read-only) ---
  aircraft(signal?: AbortSignal): Promise<Aircraft[]> {
    return api.get<Aircraft[]>('/api/aircraft', { signal });
  },
};
