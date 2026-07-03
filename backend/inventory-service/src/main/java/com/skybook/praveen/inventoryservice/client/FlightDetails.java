package com.skybook.praveen.inventoryservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;

/**
 * Local subset of flight-service's FlightResponse - only what inventory
 * creation needs (existence + status + times for context). Same decoupling
 * rationale as booking-service's FlightDetails; status kept as a plain
 * String because inventory-service only ever checks for "CANCELLED".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlightDetails(
        Long id,
        String flightNumber,
        String originAirportCode,
        String destinationAirportCode,
        LocalDateTime departureTime,
        LocalDateTime arrivalTime,
        String status
) {
}
