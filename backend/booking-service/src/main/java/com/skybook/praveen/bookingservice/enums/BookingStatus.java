package com.skybook.praveen.bookingservice.enums;

/**
 * Lifecycle of the booking itself (the PNR), independent of {@link PaymentStatus}
 * and {@link CheckInStatus} - see docs/BOOKING_SERVICE_MODULE.md section 4.
 */
public enum BookingStatus {

    CREATED,

    CONFIRMED,

    CANCELLED,

    COMPLETED
}
