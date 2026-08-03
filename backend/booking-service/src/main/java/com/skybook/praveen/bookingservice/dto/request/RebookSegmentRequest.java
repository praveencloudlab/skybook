package com.skybook.praveen.bookingservice.dto.request;

import jakarta.validation.constraints.NotNull;

/** Premium per-segment date change (ROUND_TRIP_MODULE.md §11): the flight to move the segment onto. */
public record RebookSegmentRequest(

        @NotNull(message = "newFlightId is required")
        Long newFlightId

) {
}
