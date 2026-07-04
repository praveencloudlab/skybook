package com.skybook.praveen.paymentservice.exception;

public class RefundNotFoundException extends RuntimeException {

    public RefundNotFoundException(Long id) {
        super("Refund not found with id: " + id);
    }
}
