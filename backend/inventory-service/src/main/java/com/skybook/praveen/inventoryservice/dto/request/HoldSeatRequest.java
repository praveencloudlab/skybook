package com.skybook.praveen.inventoryservice.dto.request;

import com.skybook.praveen.inventoryservice.enums.SeatType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Manual (passenger-chosen) seat hold. Carries {@code bookingPassengerId} (the
 * money-idempotency key, §6) and {@code travelClass} so inventory can enforce
 * cabin match at hold time - it owns the authoritative cabin rule (§7).
 * Symmetrical with {@link AutoHoldSeatRequest} apart from the explicit seat.
 */
public record HoldSeatRequest(

        @NotNull(message = "flightId is required")
        Long flightId,

        @NotBlank(message = "seatNumber is required")
        @Size(max = 5, message = "seatNumber must be at most 5 characters")
        String seatNumber,

        @NotNull(message = "bookingId is required")
        Long bookingId,

        @NotNull(message = "bookingPassengerId is required")
        Long bookingPassengerId,

        @NotNull(message = "travelClass is required")
        SeatType travelClass

) {
}
