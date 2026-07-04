package com.skybook.praveen.paymentservice.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** One fare line of a booking - fareType drives the refund rule for its amount. */
public record FareLineRequest(

        @NotBlank(message = "fareType is required")
        String fareType,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be positive")
        BigDecimal amount

) {
}
