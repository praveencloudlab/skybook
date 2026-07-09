package com.skybook.praveen.checkinservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;

/**
 * Local subset of flight-service's FlightResponse (anti-corruption, same
 * pattern as booking-service's FlightDetails) - design doc section 9.2.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlightCheckInDetails(
        Long id,
        String flightNumber,
        String originAirportCode,
        String destinationAirportCode,
        LocalDateTime departureTime,
        LocalDateTime arrivalTime,
        FlightCheckInStatus status
) {
}
