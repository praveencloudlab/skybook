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
    /** Where the Premium full-refund waiver ends (see {@link #premiumRefundPercent}). */
    private final int premiumWaiverHours;

    public CancellationPolicy(
            @Value("${booking.cancellation.saver-fee-percent:30}") BigDecimal saverFeePercent,
            @Value("${booking.cancellation.full-refund-hours:72}") int fullRefundHours,
            @Value("${booking.cancellation.half-refund-hours:24}") int halfRefundHours,
            @Value("${booking.cancellation.close-hours:2}") int closeHours,
            @Value("${booking.cancellation.premium-waiver-hours:6}") int premiumWaiverHours) {
        this.saverFeePercent = saverFeePercent;
        this.fullRefundHours = fullRefundHours;
        this.halfRefundHours = halfRefundHours;
        this.closeHours = closeHours;
        this.premiumWaiverHours = premiumWaiverHours;
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
     * The Premium waiver tier (FARE_RULES: "Cancellation fee: Waived",
     * "Refund: Fully refundable before departure").
     *
     * <p>Premium does NOT ride the standard 72/24 tiers - that was the live
     * defect: a Premium passenger cancelling two days out was quoted 50%,
     * losing half a fare their fare rules say is fully refundable. Its own
     * tier is deliberately flatter:
     *
     * <pre>
     *   until 6h before departure : 100% - the entitlement
     *   6h - 2h                   :  50% - late, not punished
     *   &lt; 2h                      : online cancellation closed (as for all)
     * </pre>
     *
     * <p>Six hours rather than "right up to departure" because that is where
     * the airline's own exposure begins (catering and crew are committed,
     * and a seat released six hours out rarely resells); 50% rather than a
     * hard stop because 100% at 6h01m and nothing at 5h59m is a cliff no
     * passenger would accept as fair.
     */
    public int premiumRefundPercent(LocalDateTime now, LocalDateTime departure) {
        if (now.isBefore(departure.minusHours(premiumWaiverHours))) {
            return 100;
        }
        return 50;
    }

    /**
     * Fare-rule fee first (Saver keeps {@code saverFeePercent}%), then a time
     * tier scales what survived - PER LINE, because the tier a line rides
     * depends on its fare type. refund + fareRuleFee + timePenalty always
     * equals the sum of the lines.
     *
     * <p>Pooling the lines and applying one percent (the previous shape) can
     * only ever express one fare family's rules, which is why a mixed
     * Premium+Saver booking used to refund both at the Saver tier.
     */
    public RefundComputation computeRefund(List<FareLine> lines, int refundPercent, int premiumPercent) {

        BigDecimal ruleRefundable = BigDecimal.ZERO;
        BigDecimal ruleFee = BigDecimal.ZERO;
        BigDecimal refund = BigDecimal.ZERO;

        for (FareLine line : lines) {
            BigDecimal lineRefundable;
            if ("SAVER".equalsIgnoreCase(line.fareType())) {
                BigDecimal lineFee = line.amount().multiply(saverFeePercent)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                ruleFee = ruleFee.add(lineFee);
                lineRefundable = line.amount().subtract(lineFee);
            } else {
                lineRefundable = line.amount();
            }
            ruleRefundable = ruleRefundable.add(lineRefundable);

            int tier = "PREMIUM".equalsIgnoreCase(line.fareType()) ? premiumPercent : refundPercent;
            refund = refund.add(lineRefundable.multiply(BigDecimal.valueOf(tier))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        }

        return new RefundComputation(refund, ruleFee, ruleRefundable.subtract(refund));
    }

    /** Back-compat for callers with no Premium context: one tier for every line. */
    public RefundComputation computeRefund(List<FareLine> lines, int refundPercent) {
        return computeRefund(lines, refundPercent, refundPercent);
    }

    /** Convenience for callers that only need the hours, e.g. error copy. */
    public Duration closeWindow() {
        return Duration.ofHours(closeHours);
    }
}
