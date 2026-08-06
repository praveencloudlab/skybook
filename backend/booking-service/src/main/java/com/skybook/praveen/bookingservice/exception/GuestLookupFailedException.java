package com.skybook.praveen.bookingservice.exception;

/**
 * One generic guest-lookup failure (GUEST_CHECKIN_MODULE.md §6) - unknown
 * reference, wrong surname, cancelled passenger, cancelled booking all raise
 * exactly this, so nothing in the response tells a prober which was wrong.
 *
 * <p>A dedicated exception rather than {@code ResponseStatusException} because
 * the latter renders through a forward to {@code /error}, and the ERROR
 * dispatch re-enters the security chain - where {@code /error} is not
 * permitted, so the intended 404 reached the browser as a 401. Found on the
 * first live run; the advice writes the response directly, with no forward.
 */
public class GuestLookupFailedException extends RuntimeException {

    public GuestLookupFailedException() {
        super("We couldn't find a booking matching those details.");
    }
}
