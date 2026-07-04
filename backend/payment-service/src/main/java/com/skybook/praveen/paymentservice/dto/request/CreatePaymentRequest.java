package com.skybook.praveen.paymentservice.dto.request;

import com.skybook.praveen.paymentservice.enums.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Manual/direct payment creation. The normal path is the BookingEvent
 * consumer; this exists for testing, back-office flows and Sprint 6's
 * possible synchronous fallback.
 */
public record CreatePaymentRequest(

        @NotNull(message = "bookingId is required")
        Long bookingId,

        @NotBlank(message = "bookingReference is required")
        @Size(max = 10, message = "bookingReference must be at most 10 characters")
        String bookingReference,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be positive")
        BigDecimal amount,

        @NotBlank(message = "currency is required")
        @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO code")
        String currency,

        // Defaults to CARD (the only implemented method in v1).
        PaymentMethod method,

        // Optional fare breakdown for fare-type refund rules; without it a
        // refund treats the whole amount as fully refundable.
        @Valid
        List<FareLineRequest> fareLines

) {
}
