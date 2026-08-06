package com.skybook.praveen.bookingservice.exception;

/**
 * The per-reference lookup brake tripped (GUEST_CHECKIN_MODULE.md §6): too
 * many failed attempts against this booking reference inside the window,
 * across every source and instance. Rendered by the advice, not by a forward
 * to {@code /error} (see {@link GuestLookupFailedException}).
 */
public class GuestLookupThrottledException extends RuntimeException {

    public GuestLookupThrottledException() {
        super("Too many attempts for this booking - please try again in a few minutes.");
    }
}
