package com.skybook.praveen.bookingservice.enums;

/**
 * Independent from {@link BookingStatus} - e.g. a CANCELLED booking can
 * still move to REFUNDED here. See docs/BOOKING_SERVICE_MODULE.md section 4.
 */
public enum PaymentStatus {

    PENDING,

    PAID,

    FAILED,

    REFUNDED
}
