package com.skybook.praveen.bookingservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Local subset of inventory-service's SeatHoldResponse - anti-corruption,
 * like FlightDetails. The pricing snapshot fields (SEAT_SELECTION_MODULE.md
 * §6) are what finalizeSeatAssignments persists: inventory's stored hold is
 * the pricing authority, booking never recomputes a surcharge.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryHoldDetails(
        Long id,
        String seatNumber,
        String assignmentMode,
        BigDecimal listedSurcharge,
        BigDecimal chargedSurcharge,
        String status,
        LocalDateTime expiresAt
) {
}
