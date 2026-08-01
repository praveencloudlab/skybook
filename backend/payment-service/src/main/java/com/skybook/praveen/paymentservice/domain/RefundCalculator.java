package com.skybook.praveen.paymentservice.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Fare-type refund rules (design doc section 6):
 * FLEXI / PREMIUM -> 100% refund; SAVER -> configurable cancellation fee
 * (default 30%). Mixed-fare bookings refund each line by its own rule.
 *
 * Operates on the compact fare-breakdown format stored on Payment
 * ("FLEXI:100.00;SAVER:80.00") - this class is the only reader/writer of
 * that format.
 */
@Component
public class RefundCalculator {

    /** Outcome of a refund computation. refundAmount + cancellationFee == requested total. */
    public record RefundComputation(BigDecimal refundAmount, BigDecimal cancellationFee) {
    }

    public record FareLine(String fareType, BigDecimal amount) {
    }

    private final BigDecimal saverFeePercent;

    public RefundCalculator(@Value("${payment.refund.saver-fee-percent:30}") BigDecimal saverFeePercent) {
        this.saverFeePercent = saverFeePercent;
    }

    public RefundComputation compute(List<FareLine> lines) {
        return compute(lines, 100);
    }

    /**
     * Fare rules first, then the cancellation-policy time tier scales what
     * survived (booking-service CancellationPolicy - the same percent the
     * passenger was quoted). refund + fee always equals the lines' total.
     */
    public RefundComputation compute(List<FareLine> lines, int refundPercent) {

        BigDecimal total = BigDecimal.ZERO;
        BigDecimal ruleRefundable = BigDecimal.ZERO;

        for (FareLine line : lines) {
            total = total.add(line.amount());
            if ("SAVER".equalsIgnoreCase(line.fareType())) {
                BigDecimal lineFee = line.amount()
                        .multiply(saverFeePercent)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                ruleRefundable = ruleRefundable.add(line.amount().subtract(lineFee));
            } else {
                ruleRefundable = ruleRefundable.add(line.amount());
            }
        }

        BigDecimal refund = ruleRefundable
                .multiply(BigDecimal.valueOf(refundPercent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return new RefundComputation(refund, total.subtract(refund));
    }

    // ---------------------------------------------------------------
    // Compact breakdown format ("FLEXI:100.00;SAVER:80.00")
    // ---------------------------------------------------------------

    public static String serialize(List<FareLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (FareLine line : lines) {
            if (!sb.isEmpty()) {
                sb.append(';');
            }
            sb.append(line.fareType().toUpperCase()).append(':').append(line.amount().toPlainString());
        }
        return sb.toString();
    }

    /**
     * Parses the stored breakdown. A null/blank breakdown falls back to a
     * single fully-refundable line of the given amount - payments created
     * without fare details refund in full (documented in RefundRequest).
     */
    public static List<FareLine> parse(String breakdown, BigDecimal fallbackAmount) {
        if (breakdown == null || breakdown.isBlank()) {
            return List.of(new FareLine("FLEXI", fallbackAmount));
        }
        List<FareLine> lines = new ArrayList<>();
        for (String part : breakdown.split(";")) {
            String[] pieces = part.split(":");
            if (pieces.length != 2) {
                throw new IllegalArgumentException("Malformed fare breakdown segment: " + part);
            }
            lines.add(new FareLine(pieces[0].trim(), new BigDecimal(pieces[1].trim())));
        }
        return lines;
    }
}
