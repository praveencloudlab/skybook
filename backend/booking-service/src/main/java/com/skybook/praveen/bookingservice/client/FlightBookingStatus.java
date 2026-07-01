package com.skybook.praveen.bookingservice.client;

/**
 * booking-service's own copy of the flight status values it cares about -
 * deliberately NOT importing flight-service's FlightStatus enum (there's no
 * compile dependency between the two modules, and per
 * docs/BOOKING_SERVICE_MODULE.md section 10, domain enums stay local to the
 * service that owns them even when the values happen to line up). Jackson
 * deserializes flight-service's JSON status string into this local enum.
 */
public enum FlightBookingStatus {

    SCHEDULED,

    DELAYED,

    CANCELLED,

    DEPARTED,

    ARRIVED,

    BOARDING
}
