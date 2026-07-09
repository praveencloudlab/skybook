package com.skybook.praveen.checkinservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PATCH /api/checkins/{id}/seat - design doc section 5.6. */
public record SeatChangeRequest(

        @NotBlank(message = "newSeatNumber is required")
        @Size(max = 5, message = "newSeatNumber must be at most 5 characters")
        String newSeatNumber

) {
}
