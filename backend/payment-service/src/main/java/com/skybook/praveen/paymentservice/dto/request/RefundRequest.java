package com.skybook.praveen.paymentservice.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Refund of a captured payment. Without fareLines the full remaining
 * captured amount is refunded per the payment's stored fare breakdown;
 * with fareLines only those lines are refunded (partial refund).
 * Cumulative refunds can never exceed capturedAmount (PaymentValidator).
 */
public record RefundRequest(

        @Valid
        List<FareLineRequest> fareLines,

        /**
         * Time-tier multiplier (1-100) applied AFTER the fare rules - the
         * cancellation-policy percent quoted to the passenger. Null = 100.
         * A zero-refund cancellation never reaches this endpoint (the
         * consumer skips the refund entirely).
         */
        @jakarta.validation.constraints.Min(value = 1, message = "refundPercent must be at least 1")
        @jakarta.validation.constraints.Max(value = 100, message = "refundPercent must be at most 100")
        Integer refundPercent,

        @Size(max = 500, message = "reason must be at most 500 characters")
        String reason

) {
}
