package com.skybook.praveen.inventoryservice.service.impl;

import com.skybook.praveen.inventoryservice.config.SeatPricingProperties;
import com.skybook.praveen.inventoryservice.domain.AutoSeatSelector;
import com.skybook.praveen.inventoryservice.domain.InventoryStateMachine;
import com.skybook.praveen.inventoryservice.domain.SeatAllocationValidator;
import com.skybook.praveen.inventoryservice.domain.SeatHoldExpiryCalculator;
import com.skybook.praveen.inventoryservice.domain.SeatPricingPolicy;
import com.skybook.praveen.inventoryservice.dto.request.AutoHoldSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.CreateFlightInventoryRequest;
import com.skybook.praveen.inventoryservice.dto.request.HoldSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.ReleaseSeatRequest;
import com.skybook.praveen.inventoryservice.dto.response.FlightInventoryResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatHoldResponse;
import com.skybook.praveen.inventoryservice.entity.Aircraft;
import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import com.skybook.praveen.inventoryservice.entity.FlightInventory;
import com.skybook.praveen.inventoryservice.entity.SeatHold;
import com.skybook.praveen.inventoryservice.enums.AircraftSeatStatus;
import com.skybook.praveen.inventoryservice.enums.AircraftStatus;
import com.skybook.praveen.inventoryservice.enums.InventoryHistoryType;
import com.skybook.praveen.inventoryservice.enums.InventoryStatus;
import com.skybook.praveen.inventoryservice.enums.SeatHoldStatus;
import com.skybook.praveen.inventoryservice.enums.SeatPosition;
import com.skybook.praveen.inventoryservice.enums.SeatReservationStatus;
import com.skybook.praveen.inventoryservice.enums.SeatType;
import com.skybook.praveen.inventoryservice.exception.AircraftNotFoundException;
import com.skybook.praveen.inventoryservice.exception.FlightInventoryNotFoundException;
import com.skybook.praveen.inventoryservice.exception.InventoryConflictException;
import com.skybook.praveen.inventoryservice.exception.SeatAlreadyHeldException;
import com.skybook.praveen.inventoryservice.exception.SeatAlreadyReservedException;
import com.skybook.praveen.inventoryservice.exception.SeatCabinMismatchException;
import com.skybook.praveen.inventoryservice.repository.AircraftRepository;
import com.skybook.praveen.inventoryservice.repository.AircraftSeatRepository;
import com.skybook.praveen.inventoryservice.repository.FlightInventoryRepository;
import com.skybook.praveen.inventoryservice.repository.InventoryHistoryRepository;
import com.skybook.praveen.inventoryservice.repository.SeatHoldRepository;
import com.skybook.praveen.inventoryservice.repository.SeatReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceImplTest {

    private static final long TTL_MINUTES = 15;

    @Mock
    private FlightInventoryRepository flightInventoryRepository;
    @Mock
    private AircraftRepository aircraftRepository;
    @Mock
    private AircraftSeatRepository aircraftSeatRepository;
    @Mock
    private SeatHoldRepository seatHoldRepository;
    @Mock
    private SeatReservationRepository seatReservationRepository;
    @Mock
    private InventoryHistoryRepository inventoryHistoryRepository;

    private InventoryServiceImpl inventoryService;

    private Aircraft aircraft;
    private AircraftSeat seat;
    private FlightInventory inventory;

    @BeforeEach
    void setUp() {
        // Real domain collaborators - only repositories are mocked.
        SeatPricingPolicy pricingPolicy = new SeatPricingPolicy(new SeatPricingProperties());
        inventoryService = new InventoryServiceImpl(
                flightInventoryRepository, aircraftRepository, aircraftSeatRepository,
                seatHoldRepository, seatReservationRepository, inventoryHistoryRepository,
                new InventoryStateMachine(), new SeatAllocationValidator(),
                new SeatHoldExpiryCalculator(TTL_MINUTES), pricingPolicy,
                new AutoSeatSelector(pricingPolicy));

        aircraft = Aircraft.builder()
                .id(1L).registrationNumber("VT-SKB").status(AircraftStatus.ACTIVE).totalSeats(3).build();

        seat = AircraftSeat.builder()
                .id(2L).aircraft(aircraft).seatNumber("12A").rowNumber(12)
                .seatType(SeatType.ECONOMY).position(SeatPosition.MIDDLE)
                .status(AircraftSeatStatus.ACTIVE).exitRow(false).build();

        // The pricing policy derives cabin context from the aircraft's seat map,
        // so the chosen seat must belong to it (as it always does in production).
        aircraft.getSeats().add(seat);

        inventory = FlightInventory.builder()
                .id(10L).flightId(100L).aircraft(aircraft)
                .status(InventoryStatus.OPEN)
                .totalSeats(3).availableSeats(3).heldSeats(0).reservedSeats(0).blockedSeats(0)
                .build();
    }

    // Both inventory lookups are stubbed leniently: holdSeat takes the FOR UPDATE
    // variant, releaseHold the plain one - a given test uses only one.
    private void stubHappyPathLookups() {
        lenient().when(flightInventoryRepository.findByFlightId(100L)).thenReturn(Optional.of(inventory));
        lenient().when(flightInventoryRepository.findByFlightIdForUpdate(100L)).thenReturn(Optional.of(inventory));
        lenient().when(aircraftSeatRepository.findByAircraftIdAndSeatNumber(1L, "12A")).thenReturn(Optional.of(seat));
    }

    // ---------------------------------------------------------------
    // createInventory
    // ---------------------------------------------------------------

    @Nested
    class CreateInventory {

        private final CreateFlightInventoryRequest request =
                new CreateFlightInventoryRequest(100L, 1L, 1);

        @Test
        void derivesCountsFromActiveSeatsAndRecordsHistory() {
            when(flightInventoryRepository.existsByFlightId(100L)).thenReturn(false);
            when(aircraftRepository.findById(1L)).thenReturn(Optional.of(aircraft));
            when(aircraftSeatRepository.countByAircraftIdAndStatus(1L, AircraftSeatStatus.ACTIVE)).thenReturn(3L);
            when(flightInventoryRepository.save(any(FlightInventory.class))).thenAnswer(inv -> inv.getArgument(0));

            FlightInventoryResponse response = inventoryService.createInventory(request);

            assertThat(response.totalSeats()).isEqualTo(3);
            assertThat(response.blockedSeats()).isEqualTo(1);
            assertThat(response.availableSeats()).isEqualTo(2);
            assertThat(response.heldSeats()).isZero();
            assertThat(response.reservedSeats()).isZero();
        }

        @Test
        void duplicateFlightThrowsConflict() {
            when(flightInventoryRepository.existsByFlightId(100L)).thenReturn(true);

            assertThatThrownBy(() -> inventoryService.createInventory(request))
                    .isInstanceOf(InventoryConflictException.class);
        }

        @Test
        void unknownAircraftThrows() {
            when(flightInventoryRepository.existsByFlightId(100L)).thenReturn(false);
            when(aircraftRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> inventoryService.createInventory(request))
                    .isInstanceOf(AircraftNotFoundException.class);
        }

        @Test
        void nonActiveAircraftThrows() {
            aircraft.setStatus(AircraftStatus.MAINTENANCE);
            when(flightInventoryRepository.existsByFlightId(100L)).thenReturn(false);
            when(aircraftRepository.findById(1L)).thenReturn(Optional.of(aircraft));

            assertThatThrownBy(() -> inventoryService.createInventory(request))
                    .isInstanceOf(InventoryConflictException.class)
                    .hasMessageContaining("MAINTENANCE");
        }

        @Test
        void aircraftWithoutSeatMapThrows() {
            when(flightInventoryRepository.existsByFlightId(100L)).thenReturn(false);
            when(aircraftRepository.findById(1L)).thenReturn(Optional.of(aircraft));
            when(aircraftSeatRepository.countByAircraftIdAndStatus(1L, AircraftSeatStatus.ACTIVE)).thenReturn(0L);

            assertThatThrownBy(() -> inventoryService.createInventory(request))
                    .isInstanceOf(InventoryConflictException.class)
                    .hasMessageContaining("seat map");
        }

        @Test
        void blockedExceedingSellableThrows() {
            when(flightInventoryRepository.existsByFlightId(100L)).thenReturn(false);
            when(aircraftRepository.findById(1L)).thenReturn(Optional.of(aircraft));
            when(aircraftSeatRepository.countByAircraftIdAndStatus(1L, AircraftSeatStatus.ACTIVE)).thenReturn(3L);

            assertThatThrownBy(() -> inventoryService.createInventory(
                    new CreateFlightInventoryRequest(100L, 1L, 4)))
                    .isInstanceOf(InventoryConflictException.class)
                    .hasMessageContaining("blockedSeats");
        }
    }

    // ---------------------------------------------------------------
    // holdSeat
    // ---------------------------------------------------------------

    @Nested
    class HoldSeat {

        private final HoldSeatRequest request = new HoldSeatRequest(100L, "12A", 77L, 770L, SeatType.ECONOMY);

        @Test
        void holdMovesSeatFromAvailableToHeldAndSetsExpiry() {
            stubHappyPathLookups();
            when(seatHoldRepository.existsByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    10L, 2L, SeatHoldStatus.ACTIVE)).thenReturn(false);
            when(seatReservationRepository.existsByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    10L, 2L, SeatReservationStatus.RESERVED)).thenReturn(false);
            when(seatHoldRepository.save(any(SeatHold.class))).thenAnswer(inv -> inv.getArgument(0));

            SeatHoldResponse response = inventoryService.holdSeat(request);

            assertThat(inventory.getAvailableSeats()).isEqualTo(2);
            assertThat(inventory.getHeldSeats()).isEqualTo(1);
            assertThat(response.expiresAt()).isEqualTo(response.heldAt().plusMinutes(TTL_MINUTES));
            assertThat(inventory.getHistory())
                    .anySatisfy(entry -> assertThat(entry.getHistoryType()).isEqualTo(InventoryHistoryType.SEAT_HELD));
        }

        @Test
        void lastSeatFlipsInventoryToSoldOut() {
            inventory.setAvailableSeats(1);
            inventory.setReservedSeats(2);
            stubHappyPathLookups();
            when(seatHoldRepository.existsByFlightInventoryIdAndAircraftSeatIdAndStatus(anyLong(), anyLong(), any()))
                    .thenReturn(false);
            when(seatReservationRepository.existsByFlightInventoryIdAndAircraftSeatIdAndStatus(anyLong(), anyLong(), any()))
                    .thenReturn(false);
            when(seatHoldRepository.save(any(SeatHold.class))).thenAnswer(inv -> inv.getArgument(0));

            inventoryService.holdSeat(request);

            assertThat(inventory.getStatus()).isEqualTo(InventoryStatus.SOLD_OUT);
            assertThat(inventory.getAvailableSeats()).isZero();
        }

        @Test
        void alreadyHeldSeatThrows() {
            stubHappyPathLookups();
            when(seatHoldRepository.existsByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    10L, 2L, SeatHoldStatus.ACTIVE)).thenReturn(true);

            assertThatThrownBy(() -> inventoryService.holdSeat(request))
                    .isInstanceOf(SeatAlreadyHeldException.class);
            assertThat(inventory.getAvailableSeats()).isEqualTo(3);
        }

        @Test
        void alreadyReservedSeatThrows() {
            stubHappyPathLookups();
            when(seatHoldRepository.existsByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    10L, 2L, SeatHoldStatus.ACTIVE)).thenReturn(false);
            when(seatReservationRepository.existsByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    10L, 2L, SeatReservationStatus.RESERVED)).thenReturn(true);

            assertThatThrownBy(() -> inventoryService.holdSeat(request))
                    .isInstanceOf(SeatAlreadyReservedException.class);
        }

        @Test
        void closedInventoryRejectsHolds() {
            inventory.setStatus(InventoryStatus.CLOSED);
            stubHappyPathLookups();

            assertThatThrownBy(() -> inventoryService.holdSeat(request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CLOSED");
        }

        @Test
        void unknownFlightThrows() {
            when(flightInventoryRepository.findByFlightIdForUpdate(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> inventoryService.holdSeat(
                    new HoldSeatRequest(999L, "12A", 77L, 770L, SeatType.ECONOMY)))
                    .isInstanceOf(FlightInventoryNotFoundException.class);
        }

        @Test
        void seatInWrongCabinIsRejected() {
            stubHappyPathLookups();

            // Seat is ECONOMY; a BUSINESS-class passenger cannot take it.
            assertThatThrownBy(() -> inventoryService.holdSeat(
                    new HoldSeatRequest(100L, "12A", 77L, 770L, SeatType.BUSINESS)))
                    .isInstanceOf(SeatCabinMismatchException.class);
            assertThat(inventory.getAvailableSeats()).isEqualTo(3);
        }

        @Test
        void manualHoldSnapshotsModeAndChargesListedSurcharge() {
            stubHappyPathLookups();
            lenient().when(seatHoldRepository.existsByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    anyLong(), anyLong(), any())).thenReturn(false);
            lenient().when(seatReservationRepository.existsByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    anyLong(), anyLong(), any())).thenReturn(false);
            when(seatHoldRepository.save(any(SeatHold.class))).thenAnswer(inv -> inv.getArgument(0));

            SeatHoldResponse response = inventoryService.holdSeat(request);

            // Middle economy lists at 0; MANUAL charges the listed amount.
            assertThat(response.assignmentMode()).isEqualTo(
                    com.skybook.praveen.inventoryservice.enums.SeatAssignmentMode.MANUAL);
            assertThat(response.chargedSurcharge()).isEqualByComparingTo(response.listedSurcharge());
            assertThat(response.bookingPassengerId()).isEqualTo(770L);
        }

        @Test
        void replayForSamePassengerReturnsStoredHold() {
            SeatHold stored = SeatHold.builder()
                    .id(9L).flightInventory(inventory).aircraftSeat(seat).bookingId(77L)
                    .bookingPassengerId(770L)
                    .assignmentMode(com.skybook.praveen.inventoryservice.enums.SeatAssignmentMode.MANUAL)
                    .listedSurcharge(new java.math.BigDecimal("0.00"))
                    .chargedSurcharge(new java.math.BigDecimal("0.00"))
                    .status(SeatHoldStatus.ACTIVE)
                    .heldAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusMinutes(TTL_MINUTES))
                    .build();
            lenient().when(flightInventoryRepository.findByFlightIdForUpdate(100L))
                    .thenReturn(Optional.of(inventory));
            lenient().when(aircraftSeatRepository.findByAircraftIdAndSeatNumber(1L, "12A"))
                    .thenReturn(Optional.of(seat));
            when(seatHoldRepository.findByFlightInventoryIdAndBookingPassengerIdAndStatus(
                    10L, 770L, SeatHoldStatus.ACTIVE)).thenReturn(Optional.of(stored));

            SeatHoldResponse response = inventoryService.holdSeat(request);

            // Idempotent: the stored hold is returned, no new save, counts untouched.
            assertThat(response.id()).isEqualTo(9L);
            assertThat(inventory.getAvailableSeats()).isEqualTo(3);
            org.mockito.Mockito.verify(seatHoldRepository, org.mockito.Mockito.never())
                    .save(any(SeatHold.class));
        }

        @Test
        void autoHoldForACabinTheAircraftDoesNotHaveIsAClearError() {
            // A320 with only ECONOMY seats; a FIRST-class auto-assign must say
            // "no such cabin" (§7), not a generic mismatch.
            when(flightInventoryRepository.findByFlightIdForUpdate(100L)).thenReturn(Optional.of(inventory));
            when(seatHoldRepository.findByFlightInventoryIdAndBookingPassengerIdAndStatus(
                    10L, 770L, SeatHoldStatus.ACTIVE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> inventoryService.autoHoldSeat(100L,
                    new AutoHoldSeatRequest(77L, 770L, SeatType.FIRST)))
                    .isInstanceOf(SeatCabinMismatchException.class)
                    .hasMessageContaining("no FIRST cabin");
        }

        @Test
        void autoRequestAgainstAManualHoldIsAConflict() {
            SeatHold manualHold = SeatHold.builder()
                    .id(9L).flightInventory(inventory).aircraftSeat(seat).bookingId(77L)
                    .bookingPassengerId(770L)
                    .assignmentMode(com.skybook.praveen.inventoryservice.enums.SeatAssignmentMode.MANUAL)
                    .status(SeatHoldStatus.ACTIVE)
                    .heldAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusMinutes(TTL_MINUTES))
                    .build();
            lenient().when(flightInventoryRepository.findByFlightIdForUpdate(100L))
                    .thenReturn(Optional.of(inventory));
            when(seatHoldRepository.findByFlightInventoryIdAndBookingPassengerIdAndStatus(
                    10L, 770L, SeatHoldStatus.ACTIVE)).thenReturn(Optional.of(manualHold));

            assertThatThrownBy(() -> inventoryService.autoHoldSeat(100L,
                    new AutoHoldSeatRequest(77L, 770L, SeatType.ECONOMY)))
                    .isInstanceOf(InventoryConflictException.class)
                    .hasMessageContaining("conflicting");
        }
    }

    // ---------------------------------------------------------------
    // releaseHold / expireHolds
    // ---------------------------------------------------------------

    @Nested
    class ReleaseAndExpire {

        private SeatHold activeHold() {
            return SeatHold.builder()
                    .id(5L).flightInventory(inventory).aircraftSeat(seat).bookingId(77L)
                    .status(SeatHoldStatus.ACTIVE)
                    .heldAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusMinutes(TTL_MINUTES))
                    .build();
        }

        @Test
        void releaseReturnsSeatToPool() {
            inventory.setAvailableSeats(2);
            inventory.setHeldSeats(1);
            stubHappyPathLookups();
            when(seatHoldRepository.findByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    10L, 2L, SeatHoldStatus.ACTIVE)).thenReturn(Optional.of(activeHold()));

            inventoryService.releaseHold(new ReleaseSeatRequest(100L, "12A", 77L, "user changed mind"));

            assertThat(inventory.getAvailableSeats()).isEqualTo(3);
            assertThat(inventory.getHeldSeats()).isZero();
            assertThat(inventory.getHistory())
                    .anySatisfy(entry -> assertThat(entry.getHistoryType()).isEqualTo(InventoryHistoryType.HOLD_RELEASED));
        }

        @Test
        void releaseBySomeoneElsesBookingThrows() {
            stubHappyPathLookups();
            when(seatHoldRepository.findByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    10L, 2L, SeatHoldStatus.ACTIVE)).thenReturn(Optional.of(activeHold()));

            assertThatThrownBy(() -> inventoryService.releaseHold(
                    new ReleaseSeatRequest(100L, "12A", 88L, null)))
                    .isInstanceOf(InventoryConflictException.class)
                    .hasMessageContaining("different booking");
        }

        @Test
        void releaseWithoutActiveHoldThrows() {
            stubHappyPathLookups();
            when(seatHoldRepository.findByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    10L, 2L, SeatHoldStatus.ACTIVE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> inventoryService.releaseHold(
                    new ReleaseSeatRequest(100L, "12A", 77L, null)))
                    .isInstanceOf(InventoryConflictException.class)
                    .hasMessageContaining("No active hold");
        }

        @Test
        void releaseReopensSoldOutInventory() {
            inventory.setStatus(InventoryStatus.SOLD_OUT);
            inventory.setAvailableSeats(0);
            inventory.setHeldSeats(1);
            inventory.setReservedSeats(2);
            stubHappyPathLookups();
            when(seatHoldRepository.findByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    10L, 2L, SeatHoldStatus.ACTIVE)).thenReturn(Optional.of(activeHold()));

            inventoryService.releaseHold(new ReleaseSeatRequest(100L, "12A", 77L, null));

            assertThat(inventory.getStatus()).isEqualTo(InventoryStatus.OPEN);
            assertThat(inventory.getHistory())
                    .anySatisfy(entry -> assertThat(entry.getHistoryType()).isEqualTo(InventoryHistoryType.INVENTORY_REOPENED));
        }

        @Test
        void expireHoldsSweepsEveryOverdueHoldAndRestoresCounts() {
            inventory.setAvailableSeats(1);
            inventory.setHeldSeats(2);
            SeatHold first = activeHold();
            SeatHold second = SeatHold.builder()
                    .id(6L).flightInventory(inventory)
                    .aircraftSeat(AircraftSeat.builder().id(3L).aircraft(aircraft).seatNumber("12B").build())
                    .bookingId(88L).status(SeatHoldStatus.ACTIVE)
                    .heldAt(LocalDateTime.now().minusMinutes(30))
                    .expiresAt(LocalDateTime.now().minusMinutes(15))
                    .build();
            when(seatHoldRepository.findByStatusAndExpiresAtBefore(any(), any()))
                    .thenReturn(List.of(first, second));

            int expired = inventoryService.expireHolds();

            assertThat(expired).isEqualTo(2);
            assertThat(first.getStatus()).isEqualTo(SeatHoldStatus.EXPIRED);
            assertThat(second.getStatus()).isEqualTo(SeatHoldStatus.EXPIRED);
            assertThat(inventory.getAvailableSeats()).isEqualTo(3);
            assertThat(inventory.getHeldSeats()).isZero();
        }

        @Test
        void expireHoldsWithNothingOverdueReturnsZero() {
            when(seatHoldRepository.findByStatusAndExpiresAtBefore(any(), any())).thenReturn(List.of());

            assertThat(inventoryService.expireHolds()).isZero();
        }
    }

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------

    @Test
    void closeThenReopenWalksTheStateMachine() {
        lenient().when(flightInventoryRepository.findByFlightId(100L)).thenReturn(Optional.of(inventory));

        inventoryService.closeInventory(100L, "schedule change");
        assertThat(inventory.getStatus()).isEqualTo(InventoryStatus.CLOSED);

        inventoryService.reopenInventory(100L, "schedule restored");
        assertThat(inventory.getStatus()).isEqualTo(InventoryStatus.OPEN);

        assertThat(inventory.getHistory()).extracting("historyType")
                .containsExactly(InventoryHistoryType.INVENTORY_CLOSED, InventoryHistoryType.INVENTORY_REOPENED);
    }
}
