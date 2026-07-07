package com.skybook.praveen.bookingservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;

/** Local subset of inventory-service's SeatHoldResponse - anti-corruption, like FlightDetails. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryHoldDetails(
        Long id,
        String seatNumber,
        String status,
        LocalDateTime expiresAt
) {
}
