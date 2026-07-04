package com.skybook.praveen.inventoryservice.exception;

public class SeatAlreadyHeldException extends RuntimeException {

    public SeatAlreadyHeldException(Long flightId, String seatNumber) {
        super("Seat " + seatNumber + " on flight " + flightId + " is already held");
    }
}
