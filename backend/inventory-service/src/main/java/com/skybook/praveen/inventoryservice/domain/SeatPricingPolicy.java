package com.skybook.praveen.inventoryservice.domain;

import com.skybook.praveen.inventoryservice.config.SeatPricingProperties;
import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import com.skybook.praveen.inventoryservice.enums.SeatPosition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The LISTED surcharge of a physical seat (SEAT_SELECTION_MODULE.md §3/§4):
 * what the seat is worth by its attributes, independent of whether anyone
 * pays it (an AUTO-assigned window is listed $12 but charged $0 - the
 * charged amount is the caller's concern, decided by assignment mode).
 *
 * Composition is max(applicable tiers), NOT additive (design review round 3):
 * a window exit-row front seat is charged the exit-row tier alone, not
 * $12 + $30 + $15. Pure function of (seat attributes, cabin context) -
 * no I/O, no clock, fully unit-testable.
 *
 * This class sits behind the seam the Phase-2 Pricing Service can later
 * take over; nothing outside inventory computes seat surcharges.
 */
@Component
@EnableConfigurationProperties(SeatPricingProperties.class)
public class SeatPricingPolicy {

    private final SeatPricingProperties properties;

    public SeatPricingPolicy(SeatPricingProperties properties) {
        this.properties = properties;
    }

    public BigDecimal calculateListedSurcharge(AircraftSeat seat, CabinPricingContext cabin) {

        BigDecimal max = properties.getMiddle(); // baseline: standard middle = free pool

        if (Boolean.TRUE.equals(seat.getExitRow())) {
            max = max.max(properties.getExitRow());
        }
        if (cabin.isFrontOfCabin(seat.getRowNumber())) {
            max = max.max(properties.getFrontOfCabin());
        }
        if (seat.getPosition() == SeatPosition.WINDOW) {
            max = max.max(properties.getWindow());
        }
        if (seat.getPosition() == SeatPosition.AISLE) {
            max = max.max(properties.getAisle());
        }

        return max.setScale(2, RoundingMode.HALF_UP);
    }
}
