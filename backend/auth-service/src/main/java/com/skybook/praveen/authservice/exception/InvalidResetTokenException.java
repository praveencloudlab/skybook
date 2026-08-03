package com.skybook.praveen.authservice.exception;

/**
 * A password-reset token was unknown, already spent, or expired. Thrown
 * identically for all three so a caller cannot probe which tokens exist;
 * {@link GlobalExceptionHandler} maps it to a generic {@code 400 Bad Request}.
 */
public class InvalidResetTokenException extends RuntimeException {

    public InvalidResetTokenException() {
        super("This reset link is invalid or has expired. Please request a new one.");
    }
}
