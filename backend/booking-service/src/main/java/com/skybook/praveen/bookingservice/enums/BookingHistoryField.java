package com.skybook.praveen.bookingservice.enums;

/** Which of the three independent state machines a BookingHistory row is recording a change for. */
public enum BookingHistoryField {

    BOOKING_STATUS,

    PAYMENT_STATUS,

    CHECK_IN_STATUS
}
