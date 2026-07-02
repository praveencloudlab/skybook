package com.skybook.praveen.bookingservice.domain;

import com.skybook.praveen.bookingservice.enums.FareType;
import com.skybook.praveen.bookingservice.enums.TravelClass;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class FareCalculatorTest {

    private final FareCalculator fareCalculator = new FareCalculator();

    @Test
    void appliesSaverDiscountToEconomyBaseFare() {
        BigDecimal fare = fareCalculator.calculateFare(TravelClass.ECONOMY, FareType.SAVER);
        assertThat(fare).isEqualByComparingTo("85.00");
    }

    @Test
    void flexiFareEqualsBaseFare() {
        BigDecimal fare = fareCalculator.calculateFare(TravelClass.ECONOMY, FareType.FLEXI);
        assertThat(fare).isEqualByComparingTo("100.00");
    }

    @Test
    void appliesPremiumSurchargeToBusinessBaseFare() {
        BigDecimal fare = fareCalculator.calculateFare(TravelClass.BUSINESS, FareType.PREMIUM);
        assertThat(fare).isEqualByComparingTo("437.50");
    }

    @Test
    void firstClassIsMoreExpensiveThanBusiness() {
        BigDecimal firstFlexi = fareCalculator.calculateFare(TravelClass.FIRST, FareType.FLEXI);
        BigDecimal businessFlexi = fareCalculator.calculateFare(TravelClass.BUSINESS, FareType.FLEXI);
        assertThat(firstFlexi).isGreaterThan(businessFlexi);
    }

    @Test
    void resultIsAlwaysScaledToTwoDecimalPlaces() {
        BigDecimal fare = fareCalculator.calculateFare(TravelClass.PREMIUM_ECONOMY, FareType.SAVER);
        assertThat(fare.scale()).isEqualTo(2);
    }
}
