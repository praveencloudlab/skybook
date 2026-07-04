package com.skybook.praveen.paymentservice.enums;

/**
 * Full vocabulary from day one so the enum (and column values) stay stable -
 * only CARD is implemented in v1. Requests carrying a not-yet-implemented
 * method are rejected by PaymentValidator with a clear 400, not by enum
 * parsing (design doc section 4.4).
 */
public enum PaymentMethod {

    CARD,

    UPI,

    BANK_TRANSFER,

    APPLE_PAY,

    GOOGLE_PAY,

    PAYPAL
}
