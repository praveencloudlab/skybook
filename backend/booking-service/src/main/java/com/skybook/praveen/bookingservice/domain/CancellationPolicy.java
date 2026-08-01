package com.skybook.praveen.bookingservice.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Time-based cancellation rules, evaluated against the governing departure
 * (the earliest still-upcoming flight of whatever is being cancelled):
 *
 * <pre>
 *   >= 72h before departure : 100% of the fare-rule refund
 *   24h - 72h               :  50%
 *    2h - 24h ("same day")  :   0% - cancelling is still allowed (it frees
 *                                the seat) but the fare is forfeited
 *    &lt; 2h / departed        : online cancellation CLOSED (airport desk only)
 * </pre>
 *
 * The tier multiplies what the fare rules would refund - a Saver's 30%
 * cancellation fee (payment-service RefundCalculator) still applies first,
 * so the two policies compose instead of competing. Pure logic, no I/O;
 * the same percent is shipped on the CANCELLED event so payment-service
 * refunds exactly what this class quoted.
 */
@Component
public class CancellationPolicy {

    /** Mirrors payment-service's {@code payment.refund.saver-fee-percent}. */
    private final BigDecimal saverFeePercent;
    private final int fullRefundHours;
    private final int halfRefundHours;
    private final int closeHours;

    public CancellationPolicy(
            @Value("${booking.cancellation.saver-fee-percent:30}") BigDecimal saverFeePercent,
            @Value("${booking.cancellation.full-refund-hours:72}") int fullRefundHours,
            @Value("${booking.cancellation.half-refund-hours:24}") int halfRefundHours,
            @Value("${booking.cancellation.close-hours:2}") int closeHours) {
        this.saverFeePercent = saverFeePercent;
        this.fullRefundHours = fullRefundHours;
        this.halfRefundHours = halfRefundHours;
        this.closeHours = closeHours;
    }

    /** One passenger row's money: its fare type decides the fare-rule fee. */
    public record FareLine(String fareType, BigDecimal amount) {
    }

    public record Assessment(
            boolean allowed,
            String blockedReason,
            int refundPercent,
            LocalDateTime fullRefundUntil,
            LocalDateTime halfRefundUntil,
            LocalDateTime cancelClosesAt) {
    }

    public record RefundComputation(BigDecimal refundAmount, BigDecimal fareRuleFee, BigDecimal timePenalty) {
    }

    public Assessment assess(LocalDateTime now, LocalDateTime departure) {

        LocalDateTime fullUntil = departure.minusHours(fullRefundHours);
        LocalDateTime halfUntil = departure.minusHours(halfRefundHours);
        LocalDateTime closesAt = departure.minusHours(closeHours);

        if (!now.isBefore(departure)) {
            return new Assessment(false, "This flight has already departed - the booking can no longer "
                    + "be cancelled online.", 0, fullUntil, halfUntil, closesAt);
        }
        if (!now.isBefore(closesAt)) {
            return new Assessment(false, "Online cancellation closes " + closeHours + " hours before "
                    + "departure. Please contact the airport desk.", 0, fullUntil, halfUntil, closesAt);
        }
        int percent = now.isBefore(fullUntil) ? 100 : now.isBefore(halfUntil) ? 50 : 0;
        return new Assessment(true, null, percent, fullUntil, halfUntil, closesAt);
    }

    /**
     * Fare-rule fee first (Saver keeps {@code saverFeePercent}%), then the
     * time tier scales what survived. refund + fareRuleFee + timePenalty
     * always equals the sum of the lines.
     */
    public RefundComputation computeRefund(List<FareLine> lines, int refundPercent) {

        BigDecimal ruleRefundable = BigDecimal.ZERO;
        BigDecimal ruleFee = BigDecimal.ZERO;

        for (FareLine line : lines) {
            if ("SAVER".equalsIgnoreCase(line.fareType())) {
                BigDecimal lineFee = line.amount().multiply(saverFeePercent)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                ruleFee = ruleFee.add(lineFee);
                ruleRefundable = ruleRefundable.add(line.amount().subtract(lineFee));
            } else {
                ruleRefundable = ruleRefundable.add(line.amount());
            }
        }

        BigDecimal refund = ruleRefundable.multiply(BigDecimal.valueOf(refundPercent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return new RefundComputation(refund, ruleFee, ruleRefundable.subtract(refund));
    }

    /** Convenience for callers that only need the hours, e.g. error copy. */
    public Duration closeWindow() {
        return Duration.ofHours(closeHours);
    }
}
