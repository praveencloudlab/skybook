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

        @Size(max = 500, message = "reason must be at most 500 characters")
        String reason

) {
}
