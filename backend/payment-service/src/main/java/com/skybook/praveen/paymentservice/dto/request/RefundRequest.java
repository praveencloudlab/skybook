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

        /**
         * The tier PREMIUM lines ride instead of {@code refundPercent} -
         * 100 inside the Premium waiver window, 50 after it. Null = no
         * separate Premium tier, so refundPercent applies to every line.
         */
        @jakarta.validation.constraints.Min(value = 1, message = "premiumPercent must be at least 1")
        @jakarta.validation.constraints.Max(value = 100, message = "premiumPercent must be at most 100")
        Integer premiumPercent,

        @Size(max = 500, message = "reason must be at most 500 characters")
        String reason,

        /**
         * The refund's CAUSE, unique per payment (IDEMPOTENCY_MODULE.md §3.6).
         * Event-driven refunds set it deterministically from the event, so a
         * Kafka redelivery computes the same value and the V3 unique index
         * makes the second insert impossible. Null (desk refunds) = no dedupe.
         */
        @Size(max = 120, message = "sourceReference must be at most 120 characters")
        String sourceReference

) {
    /** Convenience for the desk path, which has no event-derived cause. */
    public RefundRequest(List<FareLineRequest> fareLines, Integer refundPercent,
                         Integer premiumPercent, String reason) {
        this(fareLines, refundPercent, premiumPercent, reason, null);
    }
}
