package com.skybook.praveen.inventoryservice.enums;

/** Operational state of an airframe - drives whether its seats are sellable. */
public enum AircraftStatus {

    /** In service - inventory can be built against this aircraft. */
    ACTIVE,

    /** Temporarily out of service - existing inventory kept, no new inventory. */
    MAINTENANCE,

    /** Pulled from operations indefinitely (regulatory/technical hold). */
    GROUNDED,

    /** Permanently withdrawn - terminal state. */
    RETIRED
}
