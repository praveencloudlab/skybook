package com.skybook.praveen.inventoryservice.exception;

public class AircraftSeatNotFoundException extends RuntimeException {

    public AircraftSeatNotFoundException(Long id) {
        super("Aircraft seat not found with id: " + id);
    }

    public AircraftSeatNotFoundException(Long aircraftId, String seatNumber) {
        super("Seat " + seatNumber + " not found on aircraft with id: " + aircraftId);
    }
}
