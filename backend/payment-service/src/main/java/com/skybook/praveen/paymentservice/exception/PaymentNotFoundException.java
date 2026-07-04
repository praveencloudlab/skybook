package com.skybook.praveen.paymentservice.exception;

public class PaymentNotFoundException extends RuntimeException {

    private PaymentNotFoundException(String message) {
        super(message);
    }

    public static PaymentNotFoundException byId(Long id) {
        return new PaymentNotFoundException("Payment not found with id: " + id);
    }

    public static PaymentNotFoundException byReference(String reference) {
        return new PaymentNotFoundException("Payment not found with reference: " + reference);
    }

    public static PaymentNotFoundException byBooking(Long bookingId) {
        return new PaymentNotFoundException("Payment not found for booking id: " + bookingId);
    }
}
