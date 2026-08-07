import { api } from './client';
import type { Booking } from './bookings';
import type { BoardingPass, CheckIn } from './checkin';

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

/** See the note on the journey calls below - a guest is never sent to /sign-in. */
/*
 * Every guest call carries two things.
 *
 * silent401: a guest has no account to sign in to, so the shared client's
 * "401 -> go to /sign-in" reflex must not fire here (it once stranded
 * passengers on a login page they could not use).
 *
 * X-Skybook-Guest: this call is a GUEST errand, so the gateway should read
 * the guest cookie rather than any account session the browser also holds.
 * Without it the account session wins - which is what an agency wants on
 * its own booking pages, and what it did NOT get before this header
 * existed: a leftover guest cookie answered for them and their own
 * bookings came back "not found".
 */
const SILENT = {
  silent401: true,
  headers: { 'X-Skybook-Guest': '1' },
} as const;

export const guestApi = {
  /**
   * Exchange booking reference + last name for a booking-scoped session.
   *
   * A 404 means "no booking matching those details" for every wrong-input
   * shape (unknown reference, wrong name, cancelled passenger) - the server
   * deliberately does not say which, and neither does the UI.
   */
  async start(bookingReference: string, lastName: string): Promise<GuestSession> {
    return api.post<GuestSession>('/api/bookings/guest-session', { bookingReference, lastName }, SILENT);
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
    await api.post<void>(`/api/boarding-passes/checkin/${checkInId}/email`, { email }, SILENT);
  },

  /*
   * The rest of the guest journey hits the SAME endpoints the signed-in
   * pages use - but it must not borrow their 401 behaviour, which is the
   * defect these wrappers exist to fix.
   *
   * The shared client redirects to /sign-in on any 401, because for an
   * account holder a 401 means "your session lapsed, sign in again". A
   * guest has no account to sign in to: bouncing them to the login page
   * strands them on a screen that cannot help, and throws away the booking
   * they just looked up. Every guest call is therefore silent401, and the
   * page turns a 401 back into the lookup form with a sentence.
   */

  booking(bookingId: number): Promise<Booking> {
    return api.get<Booking>(`/api/bookings/${bookingId}`, SILENT);
  },

  checkIns(bookingId: number): Promise<CheckIn[]> {
    return api.get<CheckIn[]>(`/api/checkins/booking/${bookingId}`, SILENT);
  },

  checkIn(checkInId: number): Promise<CheckIn> {
    return api.patch<CheckIn>(`/api/checkins/${checkInId}/checkin`, undefined, SILENT);
  },

  boardingPass(checkInId: number): Promise<BoardingPass> {
    return api.get<BoardingPass>(`/api/boarding-passes/checkin/${checkInId}`, SILENT);
  },
};
