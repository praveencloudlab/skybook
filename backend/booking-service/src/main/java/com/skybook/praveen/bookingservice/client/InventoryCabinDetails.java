package com.skybook.praveen.bookingservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.skybook.praveen.bookingservice.enums.TravelClass;

/**
 * Local subset of inventory-service's CabinAvailabilityResponse -
 * anti-corruption, like FlightDetails. Availability only, never fares
 * (SEAT_SELECTION_MODULE.md §11): fares are assembled on this side, in the
 * quote. Inventory's SeatType names match TravelClass 1:1 by design.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryCabinDetails(
        TravelClass travelClass,
        int totalSeats,
        int availableSeats
) {
}
