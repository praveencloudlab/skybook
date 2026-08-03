package com.skybook.praveen.bookingservice.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Live cancellation quote for a booking: what cancelling RIGHT NOW would
 * refund and withhold, plus the tier boundaries so the UI can render the
 * charges chart and count down to the next drop. Computed fresh on every
 * call - never cached, never stored.
 */
public record CancellationPreviewResponse(

        boolean allowed,

        /** Why cancellation is blocked (checked in / window closed / already cancelled); null when allowed. */
        String blockedReason,

        /** The tier in force right now: 100, 50 or 0. */
        int refundPercent,

        /** The departure that governs the tiers - the earliest upcoming active flight. */
        LocalDateTime departureTime,

        /** 100% tier ends here (72h before departure). */
        LocalDateTime fullRefundUntil,

        /** 50% tier ends here (24h before departure) - after this, zero refund. */
        LocalDateTime halfRefundUntil,

        /** Online cancellation closes here (2h before departure). */
        LocalDateTime cancelClosesAt,

        /** True when nothing was captured yet - cancelling is free and refunds nothing. */
        boolean unpaid,

        BigDecimal totalPaid,

        /** Fare-rule charge (Saver's cancellation fee), independent of timing. */
        BigDecimal fareRuleFee,

        /** What the time tier withholds on top of the fare rules. */
        BigDecimal timePenalty,

        BigDecimal refundAmount,

        /** Per-passenger-row money so the UI can price a partial selection. */
        List<Line> lines
) {

    public record Line(
            Long bookingPassengerId,
            int segmentIndex,
            String passengerName,
            String fareType,
            BigDecimal paid,
            BigDecimal refund) {
    }
}
