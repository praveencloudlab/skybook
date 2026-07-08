package com.skybook.praveen.checkinservice.exception;

/** The requested seat cannot be taken according to inventory-service - held/reserved by someone else, or absent from the seat map. */
public class SeatUnavailableException extends RuntimeException {

    public SeatUnavailableException(Long flightId, String seatNumber, String reason) {
        super("Seat " + seatNumber + " on flight " + flightId + " is unavailable: " + reason);
    }
}
