package com.skybook.praveen.inventoryservice.domain;

import com.skybook.praveen.inventoryservice.config.SeatPricingProperties;
import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import com.skybook.praveen.inventoryservice.enums.SeatPosition;
import com.skybook.praveen.inventoryservice.enums.SeatType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEAT_SELECTION_MODULE.md §5.2: auto-assignment picks by the deterministic
 * tuple (exit rows excluded → non-front before front → MIDDLE<AISLE<WINDOW →
 * row/seat), never by price - so re-tuning surcharges can't change the pick.
 */
class AutoSeatSelectorTest {

    private final AutoSeatSelector selector =
            new AutoSeatSelector(new SeatPricingPolicy(new SeatPricingProperties()));

    // Economy cabin starting at row 10, front rows 10-11.
    private static final CabinPricingContext ECONOMY = new CabinPricingContext(10, 2);

    private static AircraftSeat seat(String number, int row, SeatPosition position, boolean exitRow) {
        return AircraftSeat.builder()
                .seatNumber(number).rowNumber(row)
                .seatType(SeatType.ECONOMY).position(position).exitRow(exitRow)
                .build();
    }

    @Test
    void middleBeatsAisleBeatsWindow() {
        Optional<AircraftSeat> pick = selector.pickPreferred(List.of(
                seat("20A", 20, SeatPosition.WINDOW, false),
                seat("20B", 20, SeatPosition.MIDDLE, false),
                seat("20C", 20, SeatPosition.AISLE, false)), ECONOMY);

        assertThat(pick).map(AircraftSeat::getSeatNumber).hasValue("20B");
    }

    @Test
    void nonFrontRowBeatsFrontRowEvenIfFrontIsAMiddle() {
        // Row 10 is front-of-cabin; the tuple prefers a non-front WINDOW over
        // a front MIDDLE (criterion 2 outranks criterion 3).
        Optional<AircraftSeat> pick = selector.pickPreferred(List.of(
                seat("10B", 10, SeatPosition.MIDDLE, false),
                seat("20A", 20, SeatPosition.WINDOW, false)), ECONOMY);

        assertThat(pick).map(AircraftSeat::getSeatNumber).hasValue("20A");
    }

    @Test
    void exitRowsAreExcludedNotMerelyLast() {
        // Only an exit-row seat left -> empty, never an ineligible assignment.
        Optional<AircraftSeat> pick = selector.pickPreferred(List.of(
                seat("15B", 15, SeatPosition.MIDDLE, true)), ECONOMY);

        assertThat(pick).isEmpty();
    }

    @Test
    void tieBreaksByRowThenSeatNumberDeterministically() {
        Optional<AircraftSeat> pick = selector.pickPreferred(List.of(
                seat("21B", 21, SeatPosition.MIDDLE, false),
                seat("20E", 20, SeatPosition.MIDDLE, false),
                seat("20B", 20, SeatPosition.MIDDLE, false)), ECONOMY);

        assertThat(pick).map(AircraftSeat::getSeatNumber).hasValue("20B");
    }
}
