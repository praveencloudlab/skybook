package com.skybook.praveen.bookingservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Pre-check-in seat change (passenger features): the wanted seat. */
public record ChangeSeatRequest(

        @NotBlank(message = "seatNumber is required")
        @Size(max = 5, message = "seatNumber must be at most 5 characters")
        String seatNumber

) {
}
