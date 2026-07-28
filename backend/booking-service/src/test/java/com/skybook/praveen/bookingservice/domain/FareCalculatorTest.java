package com.skybook.praveen.bookingservice.domain;

import com.skybook.praveen.bookingservice.enums.FareType;
import com.skybook.praveen.bookingservice.enums.TravelClass;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pricing must be DETERMINISTIC - quote, fare calendar and checkout all call
 * this and must agree to the cent - so the clock is pinned and every band of
 * the demand curve is asserted against hand-computed values.
 */
class FareCalculatorTest {

    /** Tuesday, 4 June 2030. */
    private static final Clock TODAY = Clock.fixed(Instant.parse("2030-06-04T09:00:00Z"), ZoneOffset.UTC);

    private final FareCalculator fareCalculator = new FareCalculator(TODAY);

    /** +42 days = Tuesday 16 Jul: the 31-60 day 1.00 baseline, no weekend. */
    private static final LocalDateTime NEUTRAL = LocalDateTime.of(2030, 7, 16, 10, 0);

    @Test
    void appliesSaverDiscountToEconomyBaseFare() {
        BigDecimal fare = fareCalculator.calculateFare(TravelClass.ECONOMY, FareType.SAVER, NEUTRAL);
        assertThat(fare).isEqualByComparingTo("85.00");
    }

    @Test
    void flexiFareEqualsBaseFareInTheBaselineWindow() {
        BigDecimal fare = fareCalculator.calculateFare(TravelClass.ECONOMY, FareType.FLEXI, NEUTRAL);
        assertThat(fare).isEqualByComparingTo("100.00");
    }

    @Test
    void appliesPremiumSurchargeToBusinessBaseFare() {
        BigDecimal fare = fareCalculator.calculateFare(TravelClass.BUSINESS, FareType.PREMIUM, NEUTRAL);
        assertThat(fare).isEqualByComparingTo("437.50");
    }

    @Test
    void lastMinuteDeparturePaysTheSurge() {
        // +2 days = Thursday 6 Jun: <=3 days out -> 1.60.
        BigDecimal fare = fareCalculator.calculateFare(
                TravelClass.ECONOMY, FareType.FLEXI, LocalDateTime.of(2030, 6, 6, 10, 0));
        assertThat(fare).isEqualByComparingTo("160.00");
    }

    @Test
    void farOutDepartureEarnsTheEarlyBirdDiscount() {
        // +100 days = Thursday 12 Sep: >90 days out -> 0.85.
        BigDecimal fare = fareCalculator.calculateFare(
                TravelClass.ECONOMY, FareType.FLEXI, LocalDateTime.of(2030, 9, 12, 10, 0));
        assertThat(fare).isEqualByComparingTo("85.00");
    }

    @Test
    void fridayDepartureCarriesTheGetawayPremium() {
        // +52 days = Friday 26 Jul: 1.00 demand band x 1.10 weekend.
        BigDecimal fare = fareCalculator.calculateFare(
                TravelClass.ECONOMY, FareType.FLEXI, LocalDateTime.of(2030, 7, 26, 10, 0));
        assertThat(fare).isEqualByComparingTo("110.00");
    }

    @Test
    void firstClassIsMoreExpensiveThanBusiness() {
        BigDecimal firstFlexi = fareCalculator.calculateFare(TravelClass.FIRST, FareType.FLEXI, NEUTRAL);
        BigDecimal businessFlexi = fareCalculator.calculateFare(TravelClass.BUSINESS, FareType.FLEXI, NEUTRAL);
        assertThat(firstFlexi).isGreaterThan(businessFlexi);
    }

    @Test
    void resultIsAlwaysScaledToTwoDecimalPlaces() {
        BigDecimal fare = fareCalculator.calculateFare(TravelClass.PREMIUM_ECONOMY, FareType.SAVER, NEUTRAL);
        assertThat(fare.scale()).isEqualTo(2);
    }
}
