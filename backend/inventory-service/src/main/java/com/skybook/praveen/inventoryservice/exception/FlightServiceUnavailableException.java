package com.skybook.praveen.inventoryservice.exception;

public class FlightServiceUnavailableException extends RuntimeException {

    public FlightServiceUnavailableException(Long flightId, Throwable cause) {
        super("flight-service unavailable while validating flight " + flightId, cause);
    }
}
