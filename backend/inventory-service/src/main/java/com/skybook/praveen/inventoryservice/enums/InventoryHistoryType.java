package com.skybook.praveen.inventoryservice.enums;

/** What kind of change an InventoryHistory row records (append-only audit trail). */
public enum InventoryHistoryType {

    INVENTORY_CREATED,

    SEAT_HELD,

    HOLD_RELEASED,

    HOLD_EXPIRED,

    SEAT_RESERVED,

    RESERVATION_CANCELLED,

    INVENTORY_CLOSED,

    INVENTORY_REOPENED,

    INVENTORY_SOLD_OUT
}
