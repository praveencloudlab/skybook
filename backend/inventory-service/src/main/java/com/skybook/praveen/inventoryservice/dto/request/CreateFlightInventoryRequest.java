package com.skybook.praveen.inventoryservice.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateFlightInventoryRequest(

        @NotNull(message = "flightId is required")
        Long flightId,

        @NotNull(message = "aircraftId is required")
        Long aircraftId,

        // Seats blocked for this flight only (crew rest etc.) - defaults to 0.
        @Min(value = 0, message = "blockedSeats cannot be negative")
        Integer blockedSeats

) {
}
