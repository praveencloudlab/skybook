package com.skybook.praveen.paymentservice.client;

import java.math.BigDecimal;

/**
 * Outcome of one gateway interaction (design doc section 5). rawPayload is
 * the gateway's full raw response, persisted verbatim onto the
 * PaymentTransaction ledger; durationMs is measured around the call by the
 * client implementation.
 */
public record GatewayResult(

        boolean success,

        String gatewayReference,

        String responseCode,

        String message,

        String rawPayload,

        long durationMs

) {

    public static GatewayResult succeeded(String gatewayReference, String responseCode,
                                          String message, String rawPayload, long durationMs) {
        return new GatewayResult(true, gatewayReference, responseCode, message, rawPayload, durationMs);
    }

    public static GatewayResult declined(String gatewayReference, String responseCode,
                                         String message, String rawPayload, long durationMs) {
        return new GatewayResult(false, gatewayReference, responseCode, message, rawPayload, durationMs);
    }

    /** Convenience used by GatewayDeclinedException and tests. */
    public static GatewayResult simulated(boolean success, String reference, String code,
                                          String message, BigDecimal amount, long durationMs) {
        String payload = "{\"gateway\":\"SIMULATED\",\"reference\":\"" + reference
                + "\",\"code\":\"" + code + "\",\"amount\":" + amount + ",\"message\":\"" + message + "\"}";
        return new GatewayResult(success, reference, code, message, payload, durationMs);
    }
}
