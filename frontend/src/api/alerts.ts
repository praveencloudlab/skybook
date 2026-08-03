import { api } from './client';
import type { TravelClass } from './quotes';

/**
 * Fare watch (passenger features): the server reprices watched fares hourly
 * with the same deterministic calculator checkout uses and emails the owner
 * when the fare moves - including when the NEXT rise lands, since demand
 * pricing climbs toward departure.
 */
export interface FareAlert {
  id: number;
  originAirportCode: string;
  destinationAirportCode: string;
  travelDate: string;
  travelClass: TravelClass;
  currentFare: string | number;
  lastNotifiedFare: string | number | null;
  currency: string;
}

export interface FareAlertRequest {
  originAirportCode: string;
  destinationAirportCode: string;
  travelDate: string;
  travelClass: TravelClass;
}

export const fareAlertsApi = {
  create(request: FareAlertRequest): Promise<FareAlert> {
    return api.post<FareAlert>('/api/bookings/fare-alerts', request);
  },
  mine(signal?: AbortSignal): Promise<FareAlert[]> {
    return api.get<FareAlert[]>('/api/bookings/fare-alerts', { signal });
  },
  remove(id: number): Promise<void> {
    return api.delete<void>(`/api/bookings/fare-alerts/${id}`);
  },
};
