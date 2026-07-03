package com.skybook.praveen.inventoryservice.enums;

/** Sales state of a FlightInventory record (one per flight). */
public enum InventoryStatus {

    /** Selling normally. */
    OPEN,

    /** Every sellable seat is reserved - reopens automatically if seats free up. */
    SOLD_OUT,

    /** Sales stopped deliberately (schedule change, operational hold) - manual state. */
    CLOSED
}
