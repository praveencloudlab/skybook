package com.skybook.praveen.inventoryservice.exception;

public class FlightInventoryNotFoundException extends RuntimeException {

    public FlightInventoryNotFoundException(Long flightId) {
        super("Flight inventory not found for flight id: " + flightId);
    }
}
