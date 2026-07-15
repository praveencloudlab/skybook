package com.skybook.praveen.inventoryservice.dto.request;

import com.skybook.praveen.inventoryservice.enums.SeatType;
import jakarta.validation.constraints.NotNull;

/**
 * Free auto-assignment hold (SEAT_SELECTION_MODULE.md §5.2): the passenger
 * doesn't pick a seat, so there is no {@code seatNumber} - inventory selects a
 * low-demand seat in the passenger's cabin atomically under the flight lock and
 * charges nothing. Symmetrical with {@link HoldSeatRequest} minus the seat.
 * The flightId travels in the path; the rest here.
 */
public record AutoHoldSeatRequest(

        @NotNull(message = "bookingId is required")
        Long bookingId,

        @NotNull(message = "bookingPassengerId is required")
        Long bookingPassengerId,

        @NotNull(message = "travelClass is required")
        SeatType travelClass

) {
}
