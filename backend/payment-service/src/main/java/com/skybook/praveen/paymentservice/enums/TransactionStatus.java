package com.skybook.praveen.paymentservice.enums;

/**
 * Outcome of one gateway interaction. Failures are first-class ledger rows -
 * a failed capture is recorded, not discarded (append-only ledger,
 * design doc section 3.2).
 */
public enum TransactionStatus {

    SUCCEEDED,

    FAILED
}
