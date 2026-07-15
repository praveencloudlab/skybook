package com.skybook.praveen.inventoryservice.exception;

import com.skybook.praveen.inventoryservice.enums.SeatType;

/**
 * The requested seat's cabin does not match the passenger's booked travel class
 * (SEAT_SELECTION_MODULE.md §7) - e.g. an ECONOMY ticket asking for a BUSINESS
 * seat. Inventory owns this rule authoritatively, at booking and at check-in.
 */
public class SeatCabinMismatchException extends RuntimeException {

    public SeatCabinMismatchException(String seatNumber, SeatType seatCabin, SeatType travelClass) {
        super("Seat " + seatNumber + " is in the " + seatCabin
                + " cabin but the passenger is booked in " + travelClass);
    }
}
