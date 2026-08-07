/**
 * The idempotency key for a booking INTENT (IDEMPOTENCY_MODULE.md §3.1).
 *
 * A key minted per REQUEST protects nothing - the retry has to carry the
 * ORIGINAL key. So it is minted once, when the passenger reaches the pay
 * step, and held in sessionStorage beside the journey draft: a reload, a
 * post-login bounce, or a second press all reuse it, and the server replays
 * the first booking instead of making a second.
 *
 * It is cleared when the booking succeeds, or when the passenger changes what
 * they are buying - a different flight is a different intent and deserves a
 * different key.
 */

const KEY = 'skybook.bookingIntentKey';

/** The current intent key, minting one if this is a fresh intent. */
export function bookingIntentKey(): string {
  let existing = sessionStorage.getItem(KEY);
  if (!existing) {
    existing = crypto.randomUUID();
    sessionStorage.setItem(KEY, existing);
  }
  return existing;
}

/** Done or abandoned - the next booking is a new intent. */
export function clearBookingIntent(): void {
  sessionStorage.removeItem(KEY);
}
