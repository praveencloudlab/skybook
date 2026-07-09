package com.skybook.praveen.checkinservice.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** POST /api/baggage - design doc section 5.5. Only for a CHECKED_IN passenger. */
public record CreateBaggageRequest(

        @NotNull(message = "checkInId is required")
        Long checkInId,

        @NotNull(message = "weightKg is required")
        @DecimalMin(value = "0.01", message = "weightKg must be greater than 0")
        BigDecimal weightKg

) {
}
