package com.skybook.praveen.inventoryservice.enums;

/**
 * Physical/configuration state of a seat on the airframe. Per-flight
 * sellability is tracked separately via SeatHold / SeatReservation - a seat
 * being ACTIVE here says nothing about whether it is free on a given flight.
 */
public enum AircraftSeatStatus {

    /** Installed and sellable in principle. */
    ACTIVE,

    /** Deliberately not sold (crew rest, weight/balance, regulatory block). */
    BLOCKED,

    /** Broken/unserviceable - not sellable until maintenance clears it. */
    INOPERATIVE
}
