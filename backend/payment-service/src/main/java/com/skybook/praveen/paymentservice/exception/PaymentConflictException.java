package com.skybook.praveen.paymentservice.exception;

/** Business-level conflicts: duplicate payment for a booking, refund exceeding captured amount, invalid state for the operation. */
public class PaymentConflictException extends RuntimeException {

    public PaymentConflictException(String message) {
        super(message);
    }
}
