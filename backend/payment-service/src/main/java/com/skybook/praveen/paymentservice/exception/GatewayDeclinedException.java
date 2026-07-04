package com.skybook.praveen.paymentservice.exception;

/**
 * The request was valid but the gateway declined the money - mapped to
 * 422 Unprocessable Entity, deliberately distinct from 409 state conflicts
 * (design doc section 9).
 */
public class GatewayDeclinedException extends RuntimeException {

    public GatewayDeclinedException(String operation, String responseCode, String message) {
        super("Gateway declined " + operation + " [" + responseCode + "]: " + message);
    }
}
