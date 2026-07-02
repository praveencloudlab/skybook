package com.skybook.praveen.bookingservice.exception;

public class BookingPassengerNotFoundException extends RuntimeException {

    public BookingPassengerNotFoundException(Long bookingId, Long passengerId) {
        super("Passenger " + passengerId + " not found on booking " + bookingId);
    }
}
