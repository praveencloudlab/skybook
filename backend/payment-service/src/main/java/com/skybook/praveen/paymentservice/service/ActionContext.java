package com.skybook.praveen.paymentservice.service;

/**
 * Provenance for PaymentHistory rows (design doc section 3.5) - who acted,
 * from where, correlated to what. Passed from the edge (controller/consumer)
 * down to the state machine.
 */
public record ActionContext(String actor, String source, String correlationId) {

    public static ActionContext user(String correlationId) {
        return new ActionContext("USER", "API", correlationId);
    }

    public static ActionContext kafka(String correlationId) {
        return new ActionContext("KAFKA", "BOOKING_EVENT", correlationId);
    }

    public static ActionContext system(String source, String correlationId) {
        return new ActionContext("SYSTEM", source, correlationId);
    }
}
