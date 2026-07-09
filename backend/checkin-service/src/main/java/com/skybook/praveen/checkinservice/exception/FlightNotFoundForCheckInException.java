package com.skybook.praveen.checkinservice.exception;

/** flight-service returned 404 for a flightId a check-in was being validated against. */
public class FlightNotFoundForCheckInException extends RuntimeException {

    public FlightNotFoundForCheckInException(Long flightId) {
        super("Flight not found with id: " + flightId);
    }
}
