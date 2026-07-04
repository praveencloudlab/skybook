package com.skybook.praveen.inventoryservice.domain;

import com.skybook.praveen.inventoryservice.entity.Aircraft;
import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import com.skybook.praveen.inventoryservice.entity.FlightInventory;
import com.skybook.praveen.inventoryservice.enums.AircraftSeatStatus;
import com.skybook.praveen.inventoryservice.enums.AircraftStatus;
import com.skybook.praveen.inventoryservice.enums.InventoryStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatAllocationValidatorTest {

    private final SeatAllocationValidator validator = new SeatAllocationValidator();

    private FlightInventory inventory(InventoryStatus invStatus, AircraftStatus acStatus) {
        return FlightInventory.builder()
                .flightId(100L)
                .aircraft(Aircraft.builder().registrationNumber("VT-SKB").status(acStatus).build())
                .status(invStatus)
                .build();
    }

    @Test
    void openInventoryOnActiveAircraftPasses() {
        assertThatCode(() -> validator.validateInventoryOpen(
                inventory(InventoryStatus.OPEN, AircraftStatus.ACTIVE)))
                .doesNotThrowAnyException();
    }

    @Test
    void nonActiveAircraftFailsWithAircraftReason() {
        assertThatThrownBy(() -> validator.validateInventoryOpen(
                inventory(InventoryStatus.OPEN, AircraftStatus.GROUNDED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GROUNDED");
    }

    @Test
    void closedInventoryFailsWithInventoryReason() {
        assertThatThrownBy(() -> validator.validateInventoryOpen(
                inventory(InventoryStatus.CLOSED, AircraftStatus.ACTIVE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOSED");
    }

    @Test
    void activeSeatPasses() {
        assertThatCode(() -> validator.validateSeatUsable(
                AircraftSeat.builder().seatNumber("12A").status(AircraftSeatStatus.ACTIVE).build()))
                .doesNotThrowAnyException();
    }

    @Test
    void blockedAndInoperativeSeatsFail() {
        assertThatThrownBy(() -> validator.validateSeatUsable(
                AircraftSeat.builder().seatNumber("12A").status(AircraftSeatStatus.BLOCKED).build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BLOCKED");

        assertThatThrownBy(() -> validator.validateSeatUsable(
                AircraftSeat.builder().seatNumber("12A").status(AircraftSeatStatus.INOPERATIVE).build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INOPERATIVE");
    }

    @Test
    void exitRowSeatRequiresEligibility() {
        AircraftSeat exitRowSeat = AircraftSeat.builder()
                .seatNumber("14A").status(AircraftSeatStatus.ACTIVE).exitRow(true).build();

        assertThatThrownBy(() -> validator.validateExitRowAllowed(exitRowSeat, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exit-row");

        assertThatCode(() -> validator.validateExitRowAllowed(exitRowSeat, true))
                .doesNotThrowAnyException();
    }

    @Test
    void nonExitRowSeatNeedsNoEligibility() {
        AircraftSeat normalSeat = AircraftSeat.builder()
                .seatNumber("12A").status(AircraftSeatStatus.ACTIVE).exitRow(false).build();

        assertThatCode(() -> validator.validateExitRowAllowed(normalSeat, false))
                .doesNotThrowAnyException();
    }
}
