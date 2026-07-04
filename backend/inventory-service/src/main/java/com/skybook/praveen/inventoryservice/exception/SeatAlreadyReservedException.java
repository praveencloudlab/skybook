package com.skybook.praveen.inventoryservice.exception;

public class SeatAlreadyReservedException extends RuntimeException {

    public SeatAlreadyReservedException(Long flightId, String seatNumber) {
        super("Seat " + seatNumber + " on flight " + flightId + " is already reserved");
    }
}
