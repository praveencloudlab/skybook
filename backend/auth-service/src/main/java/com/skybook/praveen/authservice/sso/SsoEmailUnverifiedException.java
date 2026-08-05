package com.skybook.praveen.authservice.sso;

/**
 * The one §4.2 rejection that happens AFTER Google authentication succeeds: an
 * unverified Google email proves nothing - trusting it would let anyone claim
 * any address by creating a Google account around it. Caught by the success
 * handler and turned into the {@code sso_email_unverified} redirect, never a
 * JSON error - the caller is a browser mid-navigation.
 */
public class SsoEmailUnverifiedException extends RuntimeException {

    public SsoEmailUnverifiedException() {
        super("The Google account's email address is not verified");
    }
}
