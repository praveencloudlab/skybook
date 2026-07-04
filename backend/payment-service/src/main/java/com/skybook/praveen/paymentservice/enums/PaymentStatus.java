package com.skybook.praveen.paymentservice.enums;

/**
 * Two-phase payment lifecycle (design doc section 4.1). Failure is split by
 * phase because the two failures have different legal continuations:
 * a failed authorization retries from scratch, a failed capture retries
 * against the still-live authorization.
 */
public enum PaymentStatus {

    /** Created, not yet sent to the gateway. */
    PENDING,

    /** Gateway declined the authorization - retry (-> PENDING) or cancel. */
    AUTHORIZATION_FAILED,

    /** Funds reserved at the gateway; not yet captured. */
    AUTHORIZED,

    /** Capture attempt failed - the authorization is still live; retry capture or void. */
    CAPTURE_FAILED,

    /** Money taken. Invoice exists from this point (and only from this point). */
    CAPTURED,

    /** Some, but not all, of the captured amount refunded. May repeat. */
    PARTIALLY_REFUNDED,

    /** Fully refunded - terminal. */
    REFUNDED,

    /** Cancelled before capture (void if authorized) - terminal. */
    CANCELLED
}
