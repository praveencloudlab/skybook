package com.skybook.praveen.bookingservice.exception;

/**
 * inventory-service could not be reached while a seat operation was
 * required. Bookings with seat inventory cannot be created blind - the
 * request fails with 502 rather than silently skipping seat control.
 */
public class InventoryServiceUnavailableException extends RuntimeException {

    public InventoryServiceUnavailableException(Long flightId, Throwable cause) {
        super("inventory-service unavailable while managing seats for flight " + flightId, cause);
    }
}
