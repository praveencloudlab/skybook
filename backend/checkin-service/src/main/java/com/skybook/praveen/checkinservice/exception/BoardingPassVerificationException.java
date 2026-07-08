package com.skybook.praveen.checkinservice.exception;

/**
 * The token was well-formed HTTP-wise but failed verification (tampered
 * signature, unknown pass, revoked, or already boarded) - mapped to 422
 * Unprocessable Entity, same distinction GatewayDeclinedException draws for
 * payment-service (design doc section 6/7).
 */
public class BoardingPassVerificationException extends RuntimeException {

    public BoardingPassVerificationException(String reason) {
        super("Boarding pass verification failed: " + reason);
    }
}
