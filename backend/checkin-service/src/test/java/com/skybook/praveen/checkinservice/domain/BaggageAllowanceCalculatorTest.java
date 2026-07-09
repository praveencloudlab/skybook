package com.skybook.praveen.checkinservice.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BaggageAllowanceCalculatorTest {

    private final BaggageAllowanceCalculator calculator = new BaggageAllowanceCalculator(
            new BigDecimal("15"), new BigDecimal("20"), new BigDecimal("25"), new BigDecimal("32"),
            new BigDecimal("10"));

    @Test
    void economySaverGetsTheReducedAllowance() {
        assertThat(calculator.allowanceFor("ECONOMY", "SAVER")).isEqualByComparingTo("15");
    }

    @Test
    void economyFlexiGetsTheFullEconomyAllowance() {
        assertThat(calculator.allowanceFor("ECONOMY", "FLEXI")).isEqualByComparingTo("20");
    }

    @Test
    void economyWithNullFareTypeDefaultsToFlexiAllowance() {
        assertThat(calculator.allowanceFor("ECONOMY", null)).isEqualByComparingTo("20");
    }

    @Test
    void premiumEconomyIgnoresFareTypeDistinction() {
        assertThat(calculator.allowanceFor("PREMIUM_ECONOMY", "SAVER")).isEqualByComparingTo("25");
        assertThat(calculator.allowanceFor("PREMIUM_ECONOMY", "FLEXI")).isEqualByComparingTo("25");
    }

    @Test
    void businessIgnoresFareTypeDistinction() {
        assertThat(calculator.allowanceFor("BUSINESS", "SAVER")).isEqualByComparingTo("32");
    }

    @Test
    void nullOrUnknownTravelClassFallsBackToEconomyFlexi() {
        assertThat(calculator.allowanceFor(null, "FLEXI")).isEqualByComparingTo("20");
        assertThat(calculator.allowanceFor("SUPERSONIC", "FLEXI")).isEqualByComparingTo("20");
    }

    @Test
    void weightUnderAllowanceIsNotExcess() {
        var result = calculator.compute(new BigDecimal("18"), "ECONOMY", "FLEXI");

        assertThat(result.allowanceKg()).isEqualByComparingTo("20");
        assertThat(result.excess()).isFalse();
        assertThat(result.excessKg()).isEqualByComparingTo("0");
        assertThat(result.excessCharge()).isNull();
    }

    @Test
    void weightExactlyAtAllowanceIsNotExcess() {
        var result = calculator.compute(new BigDecimal("20"), "ECONOMY", "FLEXI");
        assertThat(result.excess()).isFalse();
    }

    @Test
    void weightOverAllowanceIsExcessAndCharged() {
        var result = calculator.compute(new BigDecimal("25"), "ECONOMY", "SAVER"); // allowance 15

        assertThat(result.excess()).isTrue();
        assertThat(result.excessKg()).isEqualByComparingTo("10");
        assertThat(result.excessCharge()).isEqualByComparingTo("100"); // 10kg * 10/kg
    }
}
