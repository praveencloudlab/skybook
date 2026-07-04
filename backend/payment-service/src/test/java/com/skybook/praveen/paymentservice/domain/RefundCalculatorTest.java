package com.skybook.praveen.paymentservice.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundCalculatorTest {

    private final RefundCalculator calculator = new RefundCalculator(new BigDecimal("30"));

    private static RefundCalculator.FareLine line(String fareType, String amount) {
        return new RefundCalculator.FareLine(fareType, new BigDecimal(amount));
    }

    @Test
    void flexiAndPremiumRefundInFull() {
        var result = calculator.compute(List.of(line("FLEXI", "100.00"), line("PREMIUM", "50.00")));

        assertThat(result.refundAmount()).isEqualByComparingTo("150.00");
        assertThat(result.cancellationFee()).isEqualByComparingTo("0");
    }

    @Test
    void saverWithholdsTheConfiguredFee() {
        var result = calculator.compute(List.of(line("SAVER", "100.00")));

        assertThat(result.refundAmount()).isEqualByComparingTo("70.00");
        assertThat(result.cancellationFee()).isEqualByComparingTo("30.00");
    }

    @Test
    void mixedFareBookingRefundsEachLineByItsOwnRule() {
        var result = calculator.compute(List.of(
                line("FLEXI", "100.00"), line("SAVER", "80.00"), line("SAVER", "80.00")));

        // FLEXI 100 + 2x SAVER 56 = 212; fees 2x 24 = 48; total 260.
        assertThat(result.refundAmount()).isEqualByComparingTo("212.00");
        assertThat(result.cancellationFee()).isEqualByComparingTo("48.00");
        assertThat(result.refundAmount().add(result.cancellationFee())).isEqualByComparingTo("260.00");
    }

    @Test
    void feeRoundsHalfUpToTwoDecimals() {
        var result = calculator.compute(List.of(line("SAVER", "99.99")));

        // 99.99 * 30% = 29.997 -> 30.00; refund 69.99.
        assertThat(result.cancellationFee()).isEqualByComparingTo("30.00");
        assertThat(result.refundAmount()).isEqualByComparingTo("69.99");
    }

    @Test
    void fareTypeMatchingIsCaseInsensitive() {
        var result = calculator.compute(List.of(line("saver", "100.00")));

        assertThat(result.cancellationFee()).isEqualByComparingTo("30.00");
    }

    @Test
    void feePercentIsConfigurable() {
        RefundCalculator fiftyPercent = new RefundCalculator(new BigDecimal("50"));

        var result = fiftyPercent.compute(List.of(line("SAVER", "100.00")));

        assertThat(result.refundAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    void serializeAndParseRoundTrip() {
        List<RefundCalculator.FareLine> lines = List.of(line("FLEXI", "100.00"), line("SAVER", "80.50"));

        String serialized = RefundCalculator.serialize(lines);
        assertThat(serialized).isEqualTo("FLEXI:100.00;SAVER:80.50");

        List<RefundCalculator.FareLine> parsed = RefundCalculator.parse(serialized, BigDecimal.TEN);
        assertThat(parsed).hasSize(2);
        assertThat(parsed.getFirst().fareType()).isEqualTo("FLEXI");
        assertThat(parsed.get(1).amount()).isEqualByComparingTo("80.50");
    }

    @Test
    void nullBreakdownFallsBackToOneFullyRefundableLine() {
        List<RefundCalculator.FareLine> parsed = RefundCalculator.parse(null, new BigDecimal("250.00"));

        assertThat(parsed).hasSize(1);
        assertThat(parsed.getFirst().fareType()).isEqualTo("FLEXI");
        assertThat(parsed.getFirst().amount()).isEqualByComparingTo("250.00");
        assertThat(calculator.compute(parsed).refundAmount()).isEqualByComparingTo("250.00");
    }

    @Test
    void malformedBreakdownThrows() {
        assertThatThrownBy(() -> RefundCalculator.parse("FLEXI-100", BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FLEXI-100");
    }

    @Test
    void serializeOfEmptyListIsNull() {
        assertThat(RefundCalculator.serialize(List.of())).isNull();
        assertThat(RefundCalculator.serialize(null)).isNull();
    }
}
