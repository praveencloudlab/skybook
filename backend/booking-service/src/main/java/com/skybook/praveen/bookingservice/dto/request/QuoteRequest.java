package com.skybook.praveen.bookingservice.dto.request;

import jakarta.validation.constraints.NotNull;

/** POST /api/bookings/quote (SEAT_SELECTION_MODULE.md §11). */
public record QuoteRequest(

        @NotNull(message = "flightId is required")
        Long flightId

) {
}
