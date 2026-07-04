package com.skybook.praveen.inventoryservice.enums;

/** Lifecycle of a confirmed seat reservation. */
public enum SeatReservationStatus {

    /** Seat is committed to a booking. */
    RESERVED,

    /** Reservation released (booking cancelled/modified) - seat returns to the pool. */
    CANCELLED
}
