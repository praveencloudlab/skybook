package com.skybook.praveen.authservice.exception;

/**
 * A verification attempt that must fail: unknown account, no outstanding code,
 * expired code, or wrong digits. One generic message for all of them - which
 * of those it was is not the caller's to learn (no enumeration, §6).
 */
public class InvalidVerificationCodeException extends RuntimeException {

    public InvalidVerificationCodeException() {
        super("That verification code is not valid. Check the digits or request a new code.");
    }
}
