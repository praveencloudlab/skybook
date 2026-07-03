package com.skybook.praveen.inventoryservice.exception;

/**
 * Business-level conflicts that aren't seat-specific: duplicate inventory for
 * a flight, count-invariant violations, invalid state for the requested
 * operation.
 */
public class InventoryConflictException extends RuntimeException {

    public InventoryConflictException(String message) {
        super(message);
    }
}
