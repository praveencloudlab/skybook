package com.skybook.praveen.authservice.exception;

/**
 * The attempt cap on one code. A 6-digit space survives 5 guesses fine; it
 * does not survive thousands. Past the cap the only way forward is a fresh
 * code, and the message says so.
 */
public class TooManyVerificationAttemptsException extends RuntimeException {

    public TooManyVerificationAttemptsException() {
        super("Too many incorrect attempts. Request a new verification code.");
    }
}
