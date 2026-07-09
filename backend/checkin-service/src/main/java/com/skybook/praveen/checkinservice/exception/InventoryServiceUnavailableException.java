package com.skybook.praveen.checkinservice.exception;

/** inventory-service could not be reached while a seat operation was required. */
public class InventoryServiceUnavailableException extends RuntimeException {

    public InventoryServiceUnavailableException(Long flightId, Throwable cause) {
        super("inventory-service unavailable while managing seats for flight " + flightId, cause);
    }
}
