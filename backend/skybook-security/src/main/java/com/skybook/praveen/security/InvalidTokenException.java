package com.skybook.praveen.security;

/**
 * Thrown when a token fails any step of the §5 validation checklist. Carries a
 * short reason for logging; the filter never leaks it to the caller (the 401
 * body is always generic).
 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }

    public InvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
