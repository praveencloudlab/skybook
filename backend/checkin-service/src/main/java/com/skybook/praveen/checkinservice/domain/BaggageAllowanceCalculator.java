package com.skybook.praveen.checkinservice.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Baggage allowance by travelClass/fareType (design doc section 5.5) - pure
 * domain, mirrors RefundCalculator's per-line shape. SAVER only reduces the
 * allowance within ECONOMY; PREMIUM_ECONOMY/BUSINESS get a flat allowance
 * regardless of fare type (v1 simplification - not configurable per airline
 * yet, same standing deferral as BoardingGroupAssigner).
 */
@Component
public class BaggageAllowanceCalculator {

    /** excessCharge is a notional per-kg rate, informational only in v1 - not wired to payment-service. */
    public record AllowanceComputation(BigDecimal allowanceKg, boolean excess,
                                        BigDecimal excessKg, BigDecimal excessCharge) {
    }

    private final BigDecimal economySaverKg;
    private final BigDecimal economyFlexiKg;
    private final BigDecimal premiumEconomyKg;
    private final BigDecimal businessKg;
    private final BigDecimal excessChargePerKg;

    public BaggageAllowanceCalculator(
            @Value("${checkin.baggage.allowance-kg.economy-saver:15}") BigDecimal economySaverKg,
            @Value("${checkin.baggage.allowance-kg.economy-flexi:20}") BigDecimal economyFlexiKg,
            @Value("${checkin.baggage.allowance-kg.premium-economy:25}") BigDecimal premiumEconomyKg,
            @Value("${checkin.baggage.allowance-kg.business:32}") BigDecimal businessKg,
            @Value("${checkin.baggage.excess-charge-per-kg:10}") BigDecimal excessChargePerKg) {
        this.economySaverKg = economySaverKg;
        this.economyFlexiKg = economyFlexiKg;
        this.premiumEconomyKg = premiumEconomyKg;
        this.businessKg = businessKg;
        this.excessChargePerKg = excessChargePerKg;
    }

    public BigDecimal allowanceFor(String travelClass, String fareType) {

        if (travelClass == null) {
            return economyFlexiKg;
        }

        return switch (travelClass.toUpperCase()) {
            case "BUSINESS" -> businessKg;
            case "PREMIUM_ECONOMY" -> premiumEconomyKg;
            case "ECONOMY" -> "SAVER".equalsIgnoreCase(fareType) ? economySaverKg : economyFlexiKg;
            default -> economyFlexiKg;
        };
    }

    public AllowanceComputation compute(BigDecimal weightKg, String travelClass, String fareType) {

        BigDecimal allowance = allowanceFor(travelClass, fareType);
        boolean excess = weightKg.compareTo(allowance) > 0;
        BigDecimal excessKg = excess ? weightKg.subtract(allowance) : BigDecimal.ZERO;
        BigDecimal excessCharge = excess ? excessKg.multiply(excessChargePerKg) : null;

        return new AllowanceComputation(allowance, excess, excessKg, excessCharge);
    }
}
