package com.skybook.praveen.bookingservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;

/**
 * Local representation of the subset of flight-service's FlightResponse
 * that booking-service actually needs - not the whole upstream shape. Keeps
 * this module decoupled from flight-service's own DTO evolving over time.
 * flight-service's response carries several more fields (airlineCode,
 * scheduleId, audit columns, ...) that we deliberately don't map - hence
 * ignoreUnknown, otherwise Jackson would reject the payload outright.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlightDetails(
        Long id,
        String flightNumber,
        String originAirportCode,
        String destinationAirportCode,
        LocalDateTime departureTime,
        LocalDateTime arrivalTime,
        FlightBookingStatus status
) {
}
