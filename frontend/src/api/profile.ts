import { api } from './client';

/**
 * Passenger profile + saved travellers (FRONTEND_MODULE.md Module 14).
 *
 * <p>All owner-scoped on the server (identity from the token), so nothing here
 * takes a user id. Dates are ISO yyyy-MM-dd strings, matching the server's
 * LocalDate.
 */

export interface Profile {
  email: string;
  fullName: string | null;
  role: string;
  phone: string | null;
  dateOfBirth: string | null;
  nationality: string | null;
  passportNumber: string | null;
  passportExpiry: string | null;
  emergencyContactName: string | null;
  emergencyContactPhone: string | null;
  /** Account-level preferences, applied on every sign-in; null = never chosen. */
  preferredLanguage: string | null;
  preferredCurrency: string | null;
}

export interface UpdateProfileRequest {
  fullName?: string;
  phone?: string;
  dateOfBirth?: string | null;
  nationality?: string;
  passportNumber?: string;
  passportExpiry?: string | null;
  emergencyContactName?: string;
  emergencyContactPhone?: string;
  preferredLanguage?: string;
  preferredCurrency?: string;
}

export interface SavedTraveller {
  id: number;
  title: string | null;
  firstName: string;
  lastName: string;
  dateOfBirth: string | null;
  nationality: string | null;
  passportNumber: string | null;
  passportExpiry: string | null;
}

export interface SavedTravellerRequest {
  title?: string;
  firstName: string;
  lastName: string;
  dateOfBirth?: string | null;
  nationality?: string;
  passportNumber?: string;
  passportExpiry?: string | null;
}

export const profileApi = {
  get(signal?: AbortSignal): Promise<Profile> {
    return api.get<Profile>('/api/profile', { signal });
  },
  update(request: UpdateProfileRequest): Promise<Profile> {
    return api.put<Profile>('/api/profile', request);
  },
  changePassword(currentPassword: string, newPassword: string): Promise<void> {
    return api.post<void>('/api/profile/change-password', { currentPassword, newPassword });
  },
  travellers(signal?: AbortSignal): Promise<SavedTraveller[]> {
    return api.get<SavedTraveller[]>('/api/profile/travellers', { signal });
  },
  addTraveller(request: SavedTravellerRequest): Promise<SavedTraveller> {
    return api.post<SavedTraveller>('/api/profile/travellers', request);
  },
  updateTraveller(id: number, request: SavedTravellerRequest): Promise<SavedTraveller> {
    return api.put<SavedTraveller>(`/api/profile/travellers/${id}`, request);
  },
  deleteTraveller(id: number): Promise<void> {
    return api.delete<void>(`/api/profile/travellers/${id}`);
  },
};
