package com.skybook.praveen.paymentservice.enums;

/** What kind of change a PaymentHistory row records (append-only audit trail). */
public enum PaymentHistoryType {

    PAYMENT_CREATED,

    AUTHORIZED,

    AUTHORIZATION_FAILED,

    CAPTURED,

    CAPTURE_FAILED,

    CANCELLED,

    REFUND_REQUESTED,

    REFUND_COMPLETED,

    REFUND_FAILED
}
