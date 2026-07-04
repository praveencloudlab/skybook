package com.skybook.praveen.inventoryservice.exception;

public class FlightNotFoundForInventoryException extends RuntimeException {

    public FlightNotFoundForInventoryException(Long flightId) {
        super("Flight not found with id: " + flightId + " - cannot manage inventory for it");
    }
}
