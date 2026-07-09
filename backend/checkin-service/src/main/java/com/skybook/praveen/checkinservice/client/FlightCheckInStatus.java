package com.skybook.praveen.checkinservice.client;

/**
 * checkin-service's own copy of the flight status values it cares about -
 * deliberately NOT importing flight-service's FlightStatus enum, same
 * discipline as booking-service's FlightBookingStatus (no compile
 * dependency between modules; domain enums stay local to the service that
 * owns them even when the values line up). Jackson deserializes
 * flight-service's JSON status string into this local enum.
 */
public enum FlightCheckInStatus {

    SCHEDULED,

    DELAYED,

    CANCELLED,

    DEPARTED,

    ARRIVED,

    BOARDING
}
