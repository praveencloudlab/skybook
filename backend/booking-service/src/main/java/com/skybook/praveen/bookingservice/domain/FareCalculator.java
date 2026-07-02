package com.skybook.praveen.bookingservice.domain;

import com.skybook.praveen.bookingservice.enums.FareType;
import com.skybook.praveen.bookingservice.enums.TravelClass;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * ⚠️ Placeholder pricing model (docs sections 9 and 12) - there's no real
 * fare/pricing data source yet (static config table, Inventory Service,
 * whatever it ends up being), so this uses fixed base fares per travel
 * class and a fare-type multiplier just so booking creation has *something*
 * deterministic to compute totalFare from. Replace the constant maps below
 * once a real pricing source is decided; the calculateFare(...) contract
 * shouldn't need to change.
 */
@Component
public class FareCalculator {

    private static final Map<TravelClass, BigDecimal> BASE_FARE_BY_CLASS = Map.of(
            TravelClass.ECONOMY, new BigDecimal("100.00"),
            TravelClass.PREMIUM_ECONOMY, new BigDecimal("180.00"),
            TravelClass.BUSINESS, new BigDecimal("350.00"),
            TravelClass.FIRST, new BigDecimal("700.00")
    );

    private static final Map<FareType, BigDecimal> FARE_TYPE_MULTIPLIER = Map.of(
            FareType.SAVER, new BigDecimal("0.85"),
            FareType.FLEXI, new BigDecimal("1.00"),
            FareType.PREMIUM, new BigDecimal("1.25")
    );

    public BigDecimal calculateFare(TravelClass travelClass, FareType fareType) {

        BigDecimal baseFare = BASE_FARE_BY_CLASS.get(travelClass);
        BigDecimal multiplier = FARE_TYPE_MULTIPLIER.get(fareType);

        return baseFare.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    }
}
