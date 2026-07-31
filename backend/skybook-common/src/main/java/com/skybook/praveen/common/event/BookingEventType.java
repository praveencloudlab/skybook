package com.skybook.praveen.common.event;

public enum BookingEventType {

    CREATED,
    CONFIRMED,
    CANCELLED,
    EXPIRED,
    COMPLETED,

    /**
     * A watched fare moved (fare-watch feature): plain-text email only - no
     * booking behind it, so consumers other than notification-service ignore
     * it. All BookingEvent consumers must know this constant before any
     * producer sends it (deploy consumers first or together).
     */
    FARE_ALERT

}