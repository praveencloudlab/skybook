package com.skybook.praveen.inventoryservice.dto.request;

import com.skybook.praveen.inventoryservice.enums.SeatType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Confirm a seat for a booking. If an ACTIVE hold exists for the same
 * booking/seat it is confirmed (holdId optional but recommended); otherwise
 * a direct reservation is attempted.
 *
 * travelClass/maxAllowedSurcharge are OPTIONAL in this generic contract
 * (SEAT_SELECTION_MODULE.md §9, round 7) and their semantics are per-flow:
 * booking confirmation omits them (the held seat was already cabin-validated
 * and priced at hold time - no ceiling rule); the DIRECT check-in
 * reservation path supplies both, and inventory then enforces cabin match
 * and listedSurcharge <= maxAllowedSurcharge under the shared flight lock.
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

        Long holdId,

        SeatType travelClass,

        /** Check-in entitlement ceiling: the surcharge the passenger already PAID at booking. */
        BigDecimal maxAllowedSurcharge

) {
}
