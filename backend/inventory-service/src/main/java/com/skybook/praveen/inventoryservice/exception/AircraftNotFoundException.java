package com.skybook.praveen.inventoryservice.exception;

public class AircraftNotFoundException extends RuntimeException {

    public AircraftNotFoundException(Long id) {
        super("Aircraft not found with id: " + id);
    }

    public AircraftNotFoundException(String registrationNumber) {
        super("Aircraft not found with registration number: " + registrationNumber);
    }
}
