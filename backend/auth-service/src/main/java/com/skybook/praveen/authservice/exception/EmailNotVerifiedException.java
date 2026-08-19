package com.skybook.praveen.authservice.exception;

/**
 * Sign-in with correct credentials on an account that never redeemed its
 * verification code. Raised only AFTER the password check succeeds, so it
 * reveals nothing to anyone but the account's owner - who needs to be told
 * exactly this, so the client can route them to the code-entry step.
 */
public class EmailNotVerifiedException extends RuntimeException {

    public EmailNotVerifiedException() {
        super("Email not verified. Enter the verification code we sent you, or request a new one.");
    }
}
