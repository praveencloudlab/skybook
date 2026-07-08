package com.skybook.praveen.bookingservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Local subset of inventory-service's SeatReservationResponse. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryReservationDetails(
        Long id,
        String seatNumber,
        String status
) {
}
