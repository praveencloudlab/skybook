package com.skybook.praveen.bookingservice.exception;

public class SeatAlreadyBookedException extends RuntimeException {

    public SeatAlreadyBookedException(Long flightId, String seatNumber) {
        super("Seat " + seatNumber + " on flight " + flightId + " is already booked");
    }
}
