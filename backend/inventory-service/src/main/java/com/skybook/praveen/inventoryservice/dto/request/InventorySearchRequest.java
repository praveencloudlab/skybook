package com.skybook.praveen.inventoryservice.dto.request;

import com.skybook.praveen.inventoryservice.enums.InventoryStatus;
import jakarta.validation.constraints.Min;

/**
 * All fields optional filters - null means "don't filter on this", same
 * convention as BookingSearchRequest in booking-service.
 */
public record InventorySearchRequest(

        Long flightId,

        Long aircraftId,

        InventoryStatus status,

        // Only inventories with at least this many available seats.
        @Min(value = 0, message = "minAvailableSeats cannot be negative")
        Integer minAvailableSeats

) {
}
