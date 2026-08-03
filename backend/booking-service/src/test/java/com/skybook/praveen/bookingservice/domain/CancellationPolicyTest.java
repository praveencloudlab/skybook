package com.skybook.praveen.bookingservice.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier boundaries and the fare-rule/time-tier composition. All boundaries
 * are exercised from both sides - a policy that pays 100% one minute
 * before it should pay 50% is a real-money bug.
 */
class CancellationPolicyTest {

    private final CancellationPolicy policy = new CancellationPolicy(
            new BigDecimal("30"), 72, 24, 2);

    private static final LocalDateTime DEPARTURE = LocalDateTime.of(2026, 8, 10, 12, 0);

    @Test
    void moreThan72hBeforeDepartureRefunds100Percent() {
        var a = policy.assess(DEPARTURE.minusHours(73), DEPARTURE);
        assertThat(a.allowed()).isTrue();
        assertThat(a.refundPercent()).isEqualTo(100);
    }

    @Test
    void between24And72hRefunds50Percent() {
        assertThat(policy.assess(DEPARTURE.minusHours(71), DEPARTURE).refundPercent()).isEqualTo(50);
        assertThat(policy.assess(DEPARTURE.minusHours(25), DEPARTURE).refundPercent()).isEqualTo(50);
    }

    @Test
    void sameDayIsZeroRefundButStillAllowed() {
        var a = policy.assess(DEPARTURE.minusHours(23), DEPARTURE);
        assertThat(a.allowed()).isTrue();
        assertThat(a.refundPercent()).isZero();
        // Down to the closing edge it stays allowed.
        assertThat(policy.assess(DEPARTURE.minusHours(2).minusMinutes(1), DEPARTURE).allowed()).isTrue();
    }

    @Test
    void insideTwoHoursCancellationIsClosed() {
        var a = policy.assess(DEPARTURE.minusHours(2), DEPARTURE);
        assertThat(a.allowed()).isFalse();
        assertThat(a.blockedReason()).contains("closes 2 hours before");
        assertThat(policy.assess(DEPARTURE.minusMinutes(30), DEPARTURE).allowed()).isFalse();
    }

    @Test
    void afterDepartureCancellationIsClosed() {
        var a = policy.assess(DEPARTURE.plusMinutes(1), DEPARTURE);
        assertThat(a.allowed()).isFalse();
        assertThat(a.blockedReason()).contains("already departed");
    }

    @Test
    void boundariesArePublishedForTheChargesChart() {
        var a = policy.assess(DEPARTURE.minusHours(100), DEPARTURE);
        assertThat(a.fullRefundUntil()).isEqualTo(DEPARTURE.minusHours(72));
        assertThat(a.halfRefundUntil()).isEqualTo(DEPARTURE.minusHours(24));
        assertThat(a.cancelClosesAt()).isEqualTo(DEPARTURE.minusHours(2));
    }

    @Test
    void refundComposesFareRulesThenTier() {
        // Saver 100: rules keep 30 -> 70 refundable; 50% tier -> 35 back.
        var comp = policy.computeRefund(
                List.of(new CancellationPolicy.FareLine("SAVER", new BigDecimal("100.00"))), 50);
        assertThat(comp.refundAmount()).isEqualByComparingTo("35.00");
        assertThat(comp.fareRuleFee()).isEqualByComparingTo("30.00");
        assertThat(comp.timePenalty()).isEqualByComparingTo("35.00");
    }

    @Test
    void flexiRefundsScaleByTierAlone() {
        var comp = policy.computeRefund(
                List.of(new CancellationPolicy.FareLine("FLEXI", new BigDecimal("200.00"))), 50);
        assertThat(comp.refundAmount()).isEqualByComparingTo("100.00");
        assertThat(comp.fareRuleFee()).isEqualByComparingTo("0.00");
        assertThat(comp.timePenalty()).isEqualByComparingTo("100.00");
    }

    @Test
    void zeroTierForfeitsEverything() {
        var comp = policy.computeRefund(
                List.of(new CancellationPolicy.FareLine("PREMIUM", new BigDecimal("500.00"))), 0);
        assertThat(comp.refundAmount()).isEqualByComparingTo("0.00");
        assertThat(comp.timePenalty()).isEqualByComparingTo("500.00");
    }

    @Test
    void refundPlusFeesAlwaysEqualsThePaidTotal() {
        var comp = policy.computeRefund(List.of(
                new CancellationPolicy.FareLine("SAVER", new BigDecimal("80.00")),
                new CancellationPolicy.FareLine("FLEXI", new BigDecimal("120.00"))), 50);
        assertThat(comp.refundAmount().add(comp.fareRuleFee()).add(comp.timePenalty()))
                .isEqualByComparingTo("200.00");
    }
}
