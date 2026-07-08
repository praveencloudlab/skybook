package com.skybook.praveen.checkinservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Local subset of inventory-service's SeatReservationResponse - anti-corruption, like FlightCheckInDetails. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SeatReservationDetails(
        Long id,
        String seatNumber,
        String status
) {
}
