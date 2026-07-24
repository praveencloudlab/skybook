package com.skybook.praveen.authservice.exception;

/**
 * Registration attempted with an email that already exists
 * (SECURITY_HARDENING_MODULE.md §6). Mapped to a generic {@code 409 Conflict}
 * by {@link GlobalExceptionHandler} - the message is deliberately generic so it
 * cannot be used to enumerate which emails are registered.
 */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException() {
        super("Email already registered");
    }
}
