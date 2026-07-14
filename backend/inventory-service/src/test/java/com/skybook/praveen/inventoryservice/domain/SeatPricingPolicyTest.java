package com.skybook.praveen.inventoryservice.domain;

import com.skybook.praveen.inventoryservice.config.SeatPricingProperties;
import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import com.skybook.praveen.inventoryservice.enums.SeatPosition;
import com.skybook.praveen.inventoryservice.enums.SeatType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEAT_SELECTION_MODULE.md §4/§15: each attribute maps to its configured
 * tier, composition is max-of-tiers (never additive), and front-of-cabin is
 * relative to the CABIN's first row, not the aircraft's row numbering.
 */
class SeatPricingPolicyTest {

    private final SeatPricingPolicy policy = new SeatPricingPolicy(new SeatPricingProperties());

    // 777-style economy cabin starting at row 15, front rows 15-16.
    private static final CabinPricingContext ECONOMY_FROM_15 = new CabinPricingContext(15, 2);
    // Business cabin starting at row 3 - ITS front rows are 3-4.
    private static final CabinPricingContext BUSINESS_FROM_3 = new CabinPricingContext(3, 2);

    private static AircraftSeat seat(int row, SeatPosition position, boolean exitRow, SeatType type) {
        return AircraftSeat.builder()
                .seatNumber(row + (position == SeatPosition.WINDOW ? "A" : position == SeatPosition.MIDDLE ? "B" : "C"))
                .rowNumber(row)
                .seatType(type)
                .position(position)
                .exitRow(exitRow)
                .build();
    }

    @Test
    void standardMiddleEconomyIsFree() {
        BigDecimal listed = policy.calculateListedSurcharge(
                seat(30, SeatPosition.MIDDLE, false, SeatType.ECONOMY), ECONOMY_FROM_15);
        assertThat(listed).isEqualByComparingTo("0.00");
    }

    @Test
    void aisleIsTheAisleTier() {
        assertThat(policy.calculateListedSurcharge(
                seat(30, SeatPosition.AISLE, false, SeatType.ECONOMY), ECONOMY_FROM_15))
                .isEqualByComparingTo("8.00");
    }

    @Test
    void windowIsTheWindowTier() {
        assertThat(policy.calculateListedSurcharge(
                seat(30, SeatPosition.WINDOW, false, SeatType.ECONOMY), ECONOMY_FROM_15))
                .isEqualByComparingTo("12.00");
    }

    @Test
    void exitRowWindowIsTheExitRowTierNotTheSum() {
        // Window ($12) AND exit row ($30) -> $30, not $42 (max, not additive).
        assertThat(policy.calculateListedSurcharge(
                seat(20, SeatPosition.WINDOW, true, SeatType.ECONOMY), ECONOMY_FROM_15))
                .isEqualByComparingTo("30.00");
    }

    @Test
    void frontOfCabinIsRelativeToTheCabinsFirstRow() {
        // Row 15 is the economy cabin's FIRST row -> front-of-cabin ($15),
        // even though it's nowhere near the aircraft's row 1.
        assertThat(policy.calculateListedSurcharge(
                seat(15, SeatPosition.MIDDLE, false, SeatType.ECONOMY), ECONOMY_FROM_15))
                .isEqualByComparingTo("15.00");

        // Row 17 is past the 2 front rows -> back to the middle tier.
        assertThat(policy.calculateListedSurcharge(
                seat(17, SeatPosition.MIDDLE, false, SeatType.ECONOMY), ECONOMY_FROM_15))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void businessRowThreeIsItsCabinsFrontRow() {
        // Business starts at row 3 on the 777 - row 3 IS front-of-cabin there.
        assertThat(policy.calculateListedSurcharge(
                seat(3, SeatPosition.MIDDLE, false, SeatType.BUSINESS), BUSINESS_FROM_3))
                .isEqualByComparingTo("15.00");
    }

    @Test
    void frontRowWindowTakesTheHigherOfTheTwoTiers() {
        // Front-of-cabin ($15) vs window ($12) -> $15.
        assertThat(policy.calculateListedSurcharge(
                seat(15, SeatPosition.WINDOW, false, SeatType.ECONOMY), ECONOMY_FROM_15))
                .isEqualByComparingTo("15.00");
    }

    @Test
    void allAmountsAreScaleTwo() {
        BigDecimal listed = policy.calculateListedSurcharge(
                seat(30, SeatPosition.WINDOW, false, SeatType.ECONOMY), ECONOMY_FROM_15);
        assertThat(listed.scale()).isEqualTo(2);
    }
}
