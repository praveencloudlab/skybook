package com.skybook.praveen.paymentservice.enums;

/** What kind of gateway interaction a PaymentTransaction ledger row records. */
public enum TransactionType {

    AUTHORIZE,

    CAPTURE,

    /** Release of an un-captured authorization. */
    VOID,

    REFUND
}
