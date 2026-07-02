package com.skybook.praveen.bookingservice.exception;

/** flight-service couldn't be reached at all (timeout, connection refused, 5xx, ...). */
public class FlightServiceUnavailableException extends RuntimeException {

    public FlightServiceUnavailableException(Long flightId, Throwable cause) {
        super("Could not reach flight-service to validate flight " + flightId, cause);
    }
}
