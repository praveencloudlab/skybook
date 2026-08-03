package com.skybook.praveen.common.event;

public enum BookingEventType {

    CREATED,
    CONFIRMED,
    CANCELLED,

    /**
     * Passengers or a whole segment cancelled off a booking that SURVIVES
     * (PARTIALLY_CANCELLED status). Carries refundBreakdown +
     * refundTierPercent so payment-service can move the actual money, and
     * cancelledBookingPassengerIds so checkin-service can close those
     * passengers' check-ins. Like FARE_ALERT: deploy all BookingEvent
     * consumers before (or together with) the first producer of this type.
     */
    PARTIALLY_CANCELLED,

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