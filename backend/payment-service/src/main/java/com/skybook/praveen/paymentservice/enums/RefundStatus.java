package com.skybook.praveen.paymentservice.enums;

/** Lifecycle of a Refund. PENDING -> COMPLETED | FAILED; FAILED -> PENDING (retry). */
public enum RefundStatus {

    PENDING,

    COMPLETED,

    FAILED
}
