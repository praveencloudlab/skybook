package com.skybook.praveen.checkinservice.exception;

import org.springframework.http.HttpStatus;

/**
 * A refused boarding-pass email request (GUEST_CHECKIN_MODULE.md §5): not yet
 * checked in (409), or too many sends inside the window (429).
 *
 * <p>A dedicated exception rather than {@code ResponseStatusException}: the
 * latter renders through a forward to {@code /error}, and the ERROR dispatch
 * re-enters the security chain - where {@code /error} is not permitted, so
 * the intended status reached the browser as a 401. Found on the first live
 * run of the sibling endpoint in booking-service.
 */
public class BoardingPassEmailException extends RuntimeException {

    private final HttpStatus status;

    private BoardingPassEmailException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static BoardingPassEmailException notCheckedIn() {
        return new BoardingPassEmailException(HttpStatus.CONFLICT,
                "Check in first - then we can send your boarding pass.");
    }

    public static BoardingPassEmailException throttled() {
        return new BoardingPassEmailException(HttpStatus.TOO_MANY_REQUESTS,
                "That pass was emailed recently - try again in a little while.");
    }

    public HttpStatus status() {
        return status;
    }
}
