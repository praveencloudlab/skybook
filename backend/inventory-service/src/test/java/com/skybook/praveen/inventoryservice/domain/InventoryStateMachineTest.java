package com.skybook.praveen.inventoryservice.domain;

import com.skybook.praveen.inventoryservice.entity.Aircraft;
import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import com.skybook.praveen.inventoryservice.entity.FlightInventory;
import com.skybook.praveen.inventoryservice.entity.SeatHold;
import com.skybook.praveen.inventoryservice.entity.SeatReservation;
import com.skybook.praveen.inventoryservice.enums.InventoryHistoryType;
import com.skybook.praveen.inventoryservice.enums.InventoryStatus;
import com.skybook.praveen.inventoryservice.enums.SeatHoldStatus;
import com.skybook.praveen.inventoryservice.enums.SeatReservationStatus;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryStateMachineTest {

    private final InventoryStateMachine stateMachine = new InventoryStateMachine();

    private FlightInventory inventoryWith(InventoryStatus status) {
        Aircraft aircraft = Aircraft.builder().id(1L).registrationNumber("VT-SKB").build();
        FlightInventory inventory = FlightInventory.builder()
                .id(1L)
                .flightId(100L)
                .aircraft(aircraft)
                .status(status)
                .totalSeats(10)
                .availableSeats(10)
                .heldSeats(0)
                .reservedSeats(0)
                .blockedSeats(0)
                .build();
        inventory.setHistory(new ArrayList<>());
        return inventory;
    }

    private SeatHold holdWith(SeatHoldStatus status, FlightInventory inventory) {
        return SeatHold.builder()
                .id(5L)
                .flightInventory(inventory)
                .aircraftSeat(AircraftSeat.builder().id(2L).seatNumber("12A").build())
                .bookingId(77L)
                .status(status)
                .heldAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusMinutes(15))
                .build();
    }

    // ---------------------------------------------------------------
    // SeatHold transitions
    // ---------------------------------------------------------------

    @Nested
    class HoldTransitions {

        private final Map<SeatHoldStatus, Set<SeatHoldStatus>> validTransitions = Map.of(
                SeatHoldStatus.ACTIVE, Set.of(SeatHoldStatus.CONFIRMED, SeatHoldStatus.RELEASED, SeatHoldStatus.EXPIRED),
                SeatHoldStatus.CONFIRMED, Set.of(),
                SeatHoldStatus.RELEASED, Set.of(),
                SeatHoldStatus.EXPIRED, Set.of()
        );

        @Test
        void matchesTheFullGoldenTransitionTable() {
            for (SeatHoldStatus from : SeatHoldStatus.values()) {
                for (SeatHoldStatus to : SeatHoldStatus.values()) {
                    boolean expected = validTransitions.get(from).contains(to);
                    assertThat(stateMachine.canTransitionHold(from, to))
                            .as("%s -> %s", from, to)
                            .isEqualTo(expected);
                }
            }
        }

        @Test
        void releaseRecordsHoldReleasedHistory() {
            FlightInventory inventory = inventoryWith(InventoryStatus.OPEN);
            SeatHold hold = holdWith(SeatHoldStatus.ACTIVE, inventory);

            stateMachine.transitionHold(hold, SeatHoldStatus.RELEASED, "caller released");

            assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.RELEASED);
            assertThat(inventory.getHistory()).hasSize(1);
            assertThat(inventory.getHistory().getFirst().getHistoryType())
                    .isEqualTo(InventoryHistoryType.HOLD_RELEASED);
            assertThat(inventory.getHistory().getFirst().getBookingId()).isEqualTo(77L);
        }

        @Test
        void expiryRecordsHoldExpiredHistory() {
            FlightInventory inventory = inventoryWith(InventoryStatus.OPEN);
            SeatHold hold = holdWith(SeatHoldStatus.ACTIVE, inventory);

            stateMachine.transitionHold(hold, SeatHoldStatus.EXPIRED, "TTL passed");

            assertThat(inventory.getHistory().getFirst().getHistoryType())
                    .isEqualTo(InventoryHistoryType.HOLD_EXPIRED);
        }

        @Test
        void confirmationRecordsSeatReservedHistory() {
            FlightInventory inventory = inventoryWith(InventoryStatus.OPEN);
            SeatHold hold = holdWith(SeatHoldStatus.ACTIVE, inventory);

            stateMachine.transitionHold(hold, SeatHoldStatus.CONFIRMED, "confirmed");

            assertThat(inventory.getHistory().getFirst().getHistoryType())
                    .isEqualTo(InventoryHistoryType.SEAT_RESERVED);
        }

        @Test
        void transitionFromTerminalStateThrows() {
            FlightInventory inventory = inventoryWith(InventoryStatus.OPEN);
            SeatHold hold = holdWith(SeatHoldStatus.RELEASED, inventory);

            assertThatThrownBy(() -> stateMachine.transitionHold(hold, SeatHoldStatus.CONFIRMED, "nope"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("RELEASED");
            assertThat(inventory.getHistory()).isEmpty();
        }
    }

    // ---------------------------------------------------------------
    // SeatReservation transitions
    // ---------------------------------------------------------------

    @Nested
    class ReservationTransitions {

        private SeatReservation reservationWith(SeatReservationStatus status, FlightInventory inventory) {
            return SeatReservation.builder()
                    .id(9L)
                    .flightInventory(inventory)
                    .aircraftSeat(AircraftSeat.builder().id(2L).seatNumber("12A").build())
                    .bookingId(77L)
                    .status(status)
                    .reservedAt(LocalDateTime.now())
                    .build();
        }

        @Test
        void reservedCanOnlyGoToCancelled() {
            assertThat(stateMachine.canTransitionReservation(
                    SeatReservationStatus.RESERVED, SeatReservationStatus.CANCELLED)).isTrue();
            assertThat(stateMachine.canTransitionReservation(
                    SeatReservationStatus.CANCELLED, SeatReservationStatus.RESERVED)).isFalse();
        }

        @Test
        void cancelSetsCancelledAtAndRecordsHistory() {
            FlightInventory inventory = inventoryWith(InventoryStatus.OPEN);
            SeatReservation reservation = reservationWith(SeatReservationStatus.RESERVED, inventory);

            stateMachine.cancelReservation(reservation, "booking cancelled");

            assertThat(reservation.getStatus()).isEqualTo(SeatReservationStatus.CANCELLED);
            assertThat(reservation.getCancelledAt()).isNotNull();
            assertThat(inventory.getHistory().getFirst().getHistoryType())
                    .isEqualTo(InventoryHistoryType.RESERVATION_CANCELLED);
        }

        @Test
        void cancellingTwiceThrows() {
            FlightInventory inventory = inventoryWith(InventoryStatus.OPEN);
            SeatReservation reservation = reservationWith(SeatReservationStatus.CANCELLED, inventory);

            assertThatThrownBy(() -> stateMachine.cancelReservation(reservation, "again"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ---------------------------------------------------------------
    // FlightInventory transitions
    // ---------------------------------------------------------------

    @Nested
    class InventoryTransitions {

        private final Map<InventoryStatus, Set<InventoryStatus>> validTransitions = Map.of(
                InventoryStatus.OPEN, Set.of(InventoryStatus.SOLD_OUT, InventoryStatus.CLOSED),
                InventoryStatus.SOLD_OUT, Set.of(InventoryStatus.OPEN, InventoryStatus.CLOSED),
                InventoryStatus.CLOSED, Set.of(InventoryStatus.OPEN)
        );

        @Test
        void matchesTheFullGoldenTransitionTable() {
            for (InventoryStatus from : InventoryStatus.values()) {
                for (InventoryStatus to : InventoryStatus.values()) {
                    boolean expected = validTransitions.get(from).contains(to);
                    assertThat(stateMachine.canTransitionInventory(from, to))
                            .as("%s -> %s", from, to)
                            .isEqualTo(expected);
                }
            }
        }

        @Test
        void soldOutRecordsHistoryWithoutSeatOrBooking() {
            FlightInventory inventory = inventoryWith(InventoryStatus.OPEN);

            stateMachine.transitionInventory(inventory, InventoryStatus.SOLD_OUT, "last seat taken");

            assertThat(inventory.getStatus()).isEqualTo(InventoryStatus.SOLD_OUT);
            assertThat(inventory.getHistory().getFirst().getHistoryType())
                    .isEqualTo(InventoryHistoryType.INVENTORY_SOLD_OUT);
            assertThat(inventory.getHistory().getFirst().getAircraftSeat()).isNull();
            assertThat(inventory.getHistory().getFirst().getBookingId()).isNull();
        }

        @Test
        void reopenFromClosedRecordsReopenedHistory() {
            FlightInventory inventory = inventoryWith(InventoryStatus.CLOSED);

            stateMachine.transitionInventory(inventory, InventoryStatus.OPEN, "schedule restored");

            assertThat(inventory.getHistory().getFirst().getHistoryType())
                    .isEqualTo(InventoryHistoryType.INVENTORY_REOPENED);
        }

        @Test
        void openToOpenThrows() {
            FlightInventory inventory = inventoryWith(InventoryStatus.OPEN);

            assertThatThrownBy(() -> stateMachine.transitionInventory(inventory, InventoryStatus.OPEN, "noop"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ---------------------------------------------------------------
    // recordHistory (used directly by services for non-transition events)
    // ---------------------------------------------------------------

    @Test
    void recordHistorySetsChangedAtAndAppends() {
        FlightInventory inventory = inventoryWith(InventoryStatus.OPEN);
        AircraftSeat seat = AircraftSeat.builder().id(2L).seatNumber("12A").build();

        stateMachine.recordHistory(inventory, InventoryHistoryType.SEAT_HELD, seat, 77L, "hold placed");
        stateMachine.recordHistory(inventory, InventoryHistoryType.INVENTORY_CREATED, null, null, "created");

        assertThat(inventory.getHistory()).hasSize(2);
        assertThat(inventory.getHistory().getFirst().getChangedAt()).isNotNull();
        assertThat(inventory.getHistory().getFirst().getFlightInventory()).isSameAs(inventory);
        assertThat(inventory.getHistory().getFirst().getDetails()).isEqualTo("hold placed");
    }
}
