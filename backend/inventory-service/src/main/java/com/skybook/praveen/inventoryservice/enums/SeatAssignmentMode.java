package com.skybook.praveen.inventoryservice.enums;

/**
 * How a seat came to be held (SEAT_SELECTION_MODULE.md §3/§6). The mode is
 * snapshotted immutably on the {@link com.skybook.praveen.inventoryservice.entity.SeatHold}
 * so replay is money-idempotent: an AUTO retry can never silently free-ify a
 * MANUAL hold, and vice-versa (mode mismatch is a 409).
 */
public enum SeatAssignmentMode {

    /** Passenger accepted a system-picked low-demand seat - charged nothing. */
    AUTO,

    /** Passenger chose a specific seat - charged its listed surcharge. */
    MANUAL
}
