package com.skybook.praveen.inventoryservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Confirm a seat for a booking. If an ACTIVE hold exists for the same
 * booking/seat it is confirmed (holdId optional but recommended); otherwise
 * a direct reservation is attempted.
 */
public record ReserveSeatRequest(

        @NotNull(message = "flightId is required")
        Long flightId,

        @NotBlank(message = "seatNumber is required")
        @Size(max = 5, message = "seatNumber must be at most 5 characters")
        String seatNumber,

        @NotNull(message = "bookingId is required")
        Long bookingId,

        Long bookingPassengerId,

        Long holdId

) {
}
