package com.skybook.praveen.inventoryservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Release a hold or cancel a reservation for one seat of one booking. */
public record ReleaseSeatRequest(

        @NotNull(message = "flightId is required")
        Long flightId,

        @NotBlank(message = "seatNumber is required")
        @Size(max = 5, message = "seatNumber must be at most 5 characters")
        String seatNumber,

        @NotNull(message = "bookingId is required")
        Long bookingId,

        @Size(max = 500, message = "reason must be at most 500 characters")
        String reason

) {
}
