package com.skybook.praveen.authservice.exception;

/**
 * Login failed (SECURITY_HARDENING_MODULE.md §6). Thrown identically for both
 * "no such user" and "wrong password" so the two are indistinguishable to a
 * caller (no user enumeration); {@link GlobalExceptionHandler} maps it to a
 * generic {@code 401 Unauthorized}.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
