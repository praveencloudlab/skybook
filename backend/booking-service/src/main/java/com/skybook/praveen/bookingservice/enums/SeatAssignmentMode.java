package com.skybook.praveen.bookingservice.enums;

/**
 * How a passenger's seat was decided (SEAT_SELECTION_MODULE.md §8). Persisted
 * on BookingPassenger because it's part of the immutable charge record:
 * an AUTO seat is always free even if the physical seat carries a listed
 * surcharge, so the mode is needed to explain why seatSurcharge can be 0 on
 * a seat whose listed price isn't.
 */
public enum SeatAssignmentMode {

    /** System-assigned a low-demand seat; always free (chargedSurcharge = 0). */
    AUTO,

    /** Passenger chose the seat; charged its listed surcharge. */
    MANUAL
}
