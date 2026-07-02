package com.skybook.praveen.bookingservice.enums;

/**
 * Per-passenger, not per-booking - lives on {@link com.skybook.praveen.bookingservice.entity.BookingPassenger},
 * not on Booking itself, since two travelers on the same PNR can be in
 * different check-in states. See docs/BOOKING_SERVICE_MODULE.md section 3.2/4.3.
 */
public enum CheckInStatus {

    NOT_OPEN,

    OPEN,

    CHECKED_IN,

    BOARDED,

    NO_SHOW,

    CLOSED
}
