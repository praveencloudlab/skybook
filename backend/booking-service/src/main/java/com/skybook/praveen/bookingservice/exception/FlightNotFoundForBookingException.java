package com.skybook.praveen.bookingservice.exception;

/** Flight-service returned 404 for the flightId a booking was being created against. */
public class FlightNotFoundForBookingException extends RuntimeException {

    public FlightNotFoundForBookingException(Long flightId) {
        super("Flight not found with id: " + flightId);
    }
}
