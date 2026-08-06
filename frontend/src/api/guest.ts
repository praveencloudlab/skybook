import { api } from './client';

/**
 * Guest check-in (GUEST_CHECKIN_MODULE.md) - the agency-booked passenger who
 * has no SkyBook account.
 *
 * The session credential is an httpOnly cookie (`__Host-skybook_guest`) the
 * server sets and the browser carries; nothing here ever sees a token. The
 * response's `bookingId` is what the rest of the guest journey uses, so the
 * booking reference - half the credential - appears in exactly one request
 * body and never in a URL.
 */

export interface GuestSession {
  bookingId: number;
}

export const guestApi = {
  /**
   * Exchange booking reference + last name for a booking-scoped session.
   *
   * A 404 means "no booking matching those details" for every wrong-input
   * shape (unknown reference, wrong name, cancelled passenger) - the server
   * deliberately does not say which, and neither does the UI.
   */
  async start(bookingReference: string, lastName: string): Promise<GuestSession> {
    return api.post<GuestSession>('/api/bookings/guest-session', { bookingReference, lastName });
  },

  /**
   * End the session explicitly.
   *
   * Not optional politeness: the cookie is httpOnly, so the browser cannot
   * clear it, and a guest check-in is exactly the thing people do on shared
   * airport computers. "Done" has to mean done.
   */
  async end(): Promise<void> {
    await api.delete<void>('/api/bookings/guest-session');
  },

  /** Email the boarding pass for one check-in to a chosen address. */
  async emailBoardingPass(checkInId: number, email: string): Promise<void> {
    await api.post<void>(`/api/boarding-passes/checkin/${checkInId}/email`, { email });
  },
};
