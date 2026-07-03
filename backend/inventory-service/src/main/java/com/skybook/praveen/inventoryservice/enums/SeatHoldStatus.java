package com.skybook.praveen.inventoryservice.enums;

/**
 * Lifecycle of a temporary seat hold. ACTIVE is the only non-terminal state;
 * transitions are enforced by InventoryStateMachine.
 */
public enum SeatHoldStatus {

    /** Seat is held; blocks other holds/reservations until expiry. */
    ACTIVE,

    /** Hold was converted into a SeatReservation - terminal. */
    CONFIRMED,

    /** Explicitly released by the caller before expiry - terminal. */
    RELEASED,

    /** Timed out (see SeatHoldExpiryJob) - terminal. */
    EXPIRED
}
