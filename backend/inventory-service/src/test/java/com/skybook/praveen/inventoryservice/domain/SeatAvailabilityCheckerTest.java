package com.skybook.praveen.inventoryservice.domain;

import com.skybook.praveen.inventoryservice.entity.Aircraft;
import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import com.skybook.praveen.inventoryservice.entity.FlightInventory;
import com.skybook.praveen.inventoryservice.enums.AircraftSeatStatus;
import com.skybook.praveen.inventoryservice.enums.AircraftStatus;
import com.skybook.praveen.inventoryservice.enums.InventoryStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeatAvailabilityCheckerTest {

    private final SeatAvailabilityChecker checker = new SeatAvailabilityChecker();

    private FlightInventory inventory(InventoryStatus invStatus, AircraftStatus acStatus, int available) {
        return FlightInventory.builder()
                .flightId(100L)
                .aircraft(Aircraft.builder().status(acStatus).build())
                .status(invStatus)
                .totalSeats(10)
                .availableSeats(available)
                .heldSeats(0)
                .reservedSeats(0)
                .blockedSeats(0)
                .build();
    }

    private AircraftSeat seat(AircraftSeatStatus status) {
        return AircraftSeat.builder().seatNumber("12A").status(status).build();
    }

    @Test
    void openInventoryOnActiveAircraftIsOpen() {
        assertThat(checker.isInventoryOpen(inventory(InventoryStatus.OPEN, AircraftStatus.ACTIVE, 5))).isTrue();
    }

    @Test
    void closedOrSoldOutInventoryIsNotOpen() {
        assertThat(checker.isInventoryOpen(inventory(InventoryStatus.CLOSED, AircraftStatus.ACTIVE, 5))).isFalse();
        assertThat(checker.isInventoryOpen(inventory(InventoryStatus.SOLD_OUT, AircraftStatus.ACTIVE, 0))).isFalse();
    }

    @Test
    void inventoryOnNonActiveAircraftIsNotOpen() {
        assertThat(checker.isInventoryOpen(inventory(InventoryStatus.OPEN, AircraftStatus.MAINTENANCE, 5))).isFalse();
        assertThat(checker.isInventoryOpen(inventory(InventoryStatus.OPEN, AircraftStatus.GROUNDED, 5))).isFalse();
        assertThat(checker.isInventoryOpen(inventory(InventoryStatus.OPEN, AircraftStatus.RETIRED, 5))).isFalse();
    }

    @Test
    void onlyActiveSeatsAreUsable() {
        assertThat(checker.isSeatUsable(seat(AircraftSeatStatus.ACTIVE))).isTrue();
        assertThat(checker.isSeatUsable(seat(AircraftSeatStatus.BLOCKED))).isFalse();
        assertThat(checker.isSeatUsable(seat(AircraftSeatStatus.INOPERATIVE))).isFalse();
    }

    @Test
    void seatIsAvailableWhenEveryConditionHolds() {
        assertThat(checker.isSeatAvailable(
                inventory(InventoryStatus.OPEN, AircraftStatus.ACTIVE, 5),
                seat(AircraftSeatStatus.ACTIVE),
                false, false)).isTrue();
    }

    @Test
    void activeHoldBlocksAvailability() {
        assertThat(checker.isSeatAvailable(
                inventory(InventoryStatus.OPEN, AircraftStatus.ACTIVE, 5),
                seat(AircraftSeatStatus.ACTIVE),
                true, false)).isFalse();
    }

    @Test
    void activeReservationBlocksAvailability() {
        assertThat(checker.isSeatAvailable(
                inventory(InventoryStatus.OPEN, AircraftStatus.ACTIVE, 5),
                seat(AircraftSeatStatus.ACTIVE),
                false, true)).isFalse();
    }

    @Test
    void zeroAvailableSeatsBlocksAvailability() {
        assertThat(checker.isSeatAvailable(
                inventory(InventoryStatus.OPEN, AircraftStatus.ACTIVE, 0),
                seat(AircraftSeatStatus.ACTIVE),
                false, false)).isFalse();
    }
}
