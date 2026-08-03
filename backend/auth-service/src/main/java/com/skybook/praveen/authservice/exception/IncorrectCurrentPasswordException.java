package com.skybook.praveen.authservice.exception;

/**
 * The current password given to a signed-in password change did not match.
 * {@link GlobalExceptionHandler} maps it to {@code 400 Bad Request} with a clear
 * message - there is no enumeration concern here (the caller is already
 * authenticated as this account).
 */
public class IncorrectCurrentPasswordException extends RuntimeException {

    public IncorrectCurrentPasswordException() {
        super("Current password is incorrect");
    }
}
