package com.skybook.praveen.flightservice.exception;

public class FlightScheduleNotFoundException extends RuntimeException {

    public FlightScheduleNotFoundException(Long id) {
        super("Flight schedule not found with id: " + id);
    }
}
