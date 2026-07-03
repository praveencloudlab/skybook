package com.skybook.praveen.inventoryservice.exception;

public class SeatNotAvailableException extends RuntimeException {

    public SeatNotAvailableException(Long flightId, String seatNumber, String reason) {
        super("Seat " + seatNumber + " on flight " + flightId + " is not available: " + reason);
    }
}
