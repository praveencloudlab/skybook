package com.skybook.praveen.inventoryservice.service.impl;

import com.skybook.praveen.inventoryservice.config.SeatPricingProperties;
import com.skybook.praveen.inventoryservice.domain.AutoSeatSelector;
import com.skybook.praveen.inventoryservice.domain.InventoryStateMachine;
import com.skybook.praveen.inventoryservice.domain.SeatAllocationValidator;
import com.skybook.praveen.inventoryservice.domain.SeatHoldExpiryCalculator;
import com.skybook.praveen.inventoryservice.domain.SeatPricingPolicy;
import com.skybook.praveen.inventoryservice.dto.request.ReleaseSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.ReserveSeatRequest;
import com.skybook.praveen.inventoryservice.dto.response.SeatReservationResponse;
import com.skybook.praveen.inventoryservice.entity.Aircraft;
import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import com.skybook.praveen.inventoryservice.entity.FlightInventory;
import com.skybook.praveen.inventoryservice.entity.SeatHold;
import com.skybook.praveen.inventoryservice.entity.SeatReservation;
import com.skybook.praveen.inventoryservice.enums.AircraftSeatStatus;
import com.skybook.praveen.inventoryservice.enums.AircraftStatus;
import com.skybook.praveen.inventoryservice.enums.InventoryHistoryType;
import com.skybook.praveen.inventoryservice.enums.InventoryStatus;
import com.skybook.praveen.inventoryservice.enums.SeatHoldStatus;
import com.skybook.praveen.inventoryservice.enums.SeatPosition;
import com.skybook.praveen.inventoryservice.enums.SeatReservationStatus;
import com.skybook.praveen.inventoryservice.enums.SeatType;
import com.skybook.praveen.inventoryservice.exception.InventoryConflictException;
import com.skybook.praveen.inventoryservice.exception.SeatAlreadyHeldException;
import com.skybook.praveen.inventoryservice.exception.SeatAlreadyReservedException;
import com.skybook.praveen.inventoryservice.exception.SeatCabinMismatchException;
import com.skybook.praveen.inventoryservice.exception.SeatHoldExpiredException;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeatReservationServiceImplTest {

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

    private SeatReservationServiceImpl reservationService;

    private Aircraft aircraft;
    private AircraftSeat seat;
    private FlightInventory inventory;

    @BeforeEach
    void setUp() {
        InventoryStateMachine stateMachine = new InventoryStateMachine();
        SeatAllocationValidator validator = new SeatAllocationValidator();
        SeatHoldExpiryCalculator calculator = new SeatHoldExpiryCalculator(TTL_MINUTES);

        // Real InventoryServiceImpl (with mocked repositories) so the count
        // bookkeeping shared between the two services is exercised for real.
        SeatPricingPolicy pricingPolicy = new SeatPricingPolicy(new SeatPricingProperties());
        InventoryServiceImpl inventoryService = new InventoryServiceImpl(
                flightInventoryRepository, aircraftRepository, aircraftSeatRepository,
                seatHoldRepository, seatReservationRepository, inventoryHistoryRepository,
                stateMachine, validator, calculator, pricingPolicy,
                new AutoSeatSelector(pricingPolicy));

        reservationService = new SeatReservationServiceImpl(
                seatReservationRepository, seatHoldRepository,
                stateMachine, validator, calculator, inventoryService);

        aircraft = Aircraft.builder()
                .id(1L).registrationNumber("VT-SKB").status(AircraftStatus.ACTIVE).totalSeats(3).build();

        seat = AircraftSeat.builder()
                .id(2L).aircraft(aircraft).seatNumber("12A").rowNumber(12)
                .seatType(SeatType.ECONOMY).position(SeatPosition.WINDOW)
                .status(AircraftSeatStatus.ACTIVE).exitRow(false).build();
        // The §9 ceiling check prices the seat against its cabin's context,
        // which derives from the aircraft's seat map.
        aircraft.getSeats().add(seat);

        inventory = FlightInventory.builder()
                .id(10L).flightId(100L).aircraft(aircraft)
                .status(InventoryStatus.OPEN)
                .totalSeats(3).availableSeats(3).heldSeats(0).reservedSeats(0).blockedSeats(0)
                .build();

        // reserveSeat takes the pessimistic FOR UPDATE lookup (§5.3);
        // cancelReservation keeps the plain one - stub both leniently.
        lenient().when(flightInventoryRepository.findByFlightId(100L)).thenReturn(Optional.of(inventory));
        lenient().when(flightInventoryRepository.findByFlightIdForUpdate(100L)).thenReturn(Optional.of(inventory));
        when(aircraftSeatRepository.findByAircraftIdAndSeatNumber(1L, "12A")).thenReturn(Optional.of(seat));
    }

    private SeatHold activeHold(Long bookingId) {
        return SeatHold.builder()
                .id(5L).flightInventory(inventory).aircraftSeat(seat).bookingId(bookingId)
                .status(SeatHoldStatus.ACTIVE)
                .heldAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusMinutes(TTL_MINUTES))
                .build();
    }

    private void stubNoExistingReservation() {
        when(seatReservationRepository.existsByFlightInventoryIdAndAircraftSeatIdAndStatus(
                10L, 2L, SeatReservationStatus.RESERVED)).thenReturn(false);
    }

    private void stubSaveEcho() {
        when(seatReservationRepository.save(any(SeatReservation.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---------------------------------------------------------------
    // reserveSeat - from hold
    // ---------------------------------------------------------------

    @Nested
    class ReserveFromHold {

        @Test
        void explicitHoldIdConfirmsTheHold() {
            SeatHold hold = activeHold(77L);
            stubNoExistingReservation();
            stubSaveEcho();
            inventory.setAvailableSeats(2);
            inventory.setHeldSeats(1);
            when(seatHoldRepository.findById(5L)).thenReturn(Optional.of(hold));

            SeatReservationResponse response = reservationService.reserveSeat(
                    new ReserveSeatRequest(100L, "12A", 77L, 200L, 5L, null, null));

            assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.CONFIRMED);
            assertThat(inventory.getHeldSeats()).isZero();
            assertThat(inventory.getReservedSeats()).isEqualTo(1);
            assertThat(inventory.getAvailableSeats()).isEqualTo(2); // untouched - already decremented at hold time
            assertThat(response.originatingHoldId()).isEqualTo(5L);
            assertThat(response.bookingPassengerId()).isEqualTo(200L);
        }

        @Test
        void bookingsOwnHoldIsResolvedWithoutHoldId() {
            SeatHold hold = activeHold(77L);
            stubNoExistingReservation();
            stubSaveEcho();
            inventory.setAvailableSeats(2);
            inventory.setHeldSeats(1);
            when(seatHoldRepository.findByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    10L, 2L, SeatHoldStatus.ACTIVE)).thenReturn(Optional.of(hold));

            SeatReservationResponse response = reservationService.reserveSeat(
                    new ReserveSeatRequest(100L, "12A", 77L, null, null, null, null));

            assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.CONFIRMED);
            assertThat(response.originatingHoldId()).isEqualTo(5L);
        }

        @Test
        void someoneElsesHoldBlocksTheSeat() {
            stubNoExistingReservation();
            when(seatHoldRepository.findByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    10L, 2L, SeatHoldStatus.ACTIVE)).thenReturn(Optional.of(activeHold(88L)));

            assertThatThrownBy(() -> reservationService.reserveSeat(
                    new ReserveSeatRequest(100L, "12A", 77L, null, null, null, null)))
                    .isInstanceOf(SeatAlreadyHeldException.class);
        }

        @Test
        void expiredHoldThrowsGone() {
            SeatHold hold = activeHold(77L);
            hold.setExpiresAt(LocalDateTime.now().minusMinutes(1));
            stubNoExistingReservation();
            when(seatHoldRepository.findById(5L)).thenReturn(Optional.of(hold));

            assertThatThrownBy(() -> reservationService.reserveSeat(
                    new ReserveSeatRequest(100L, "12A", 77L, null, 5L, null, null)))
                    .isInstanceOf(SeatHoldExpiredException.class);
        }

        @Test
        void unknownHoldIdThrowsConflict() {
            stubNoExistingReservation();
            when(seatHoldRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> reservationService.reserveSeat(
                    new ReserveSeatRequest(100L, "12A", 77L, null, 999L, null, null)))
                    .isInstanceOf(InventoryConflictException.class)
                    .hasMessageContaining("999");
        }

        @Test
        void holdForDifferentSeatThrowsConflict() {
            SeatHold hold = activeHold(77L);
            hold.setAircraftSeat(AircraftSeat.builder().id(3L).aircraft(aircraft).seatNumber("12B").build());
            stubNoExistingReservation();
            when(seatHoldRepository.findById(5L)).thenReturn(Optional.of(hold));

            assertThatThrownBy(() -> reservationService.reserveSeat(
                    new ReserveSeatRequest(100L, "12A", 77L, null, 5L, null, null)))
                    .isInstanceOf(InventoryConflictException.class)
                    .hasMessageContaining("different flight/seat");
        }
    }

    // ---------------------------------------------------------------
    // reserveSeat - direct
    // ---------------------------------------------------------------

    @Nested
    class ReserveDirect {

        @Test
        void directReservationTakesFromAvailable() {
            stubNoExistingReservation();
            stubSaveEcho();
            when(seatHoldRepository.findByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    10L, 2L, SeatHoldStatus.ACTIVE)).thenReturn(Optional.empty());

            SeatReservationResponse response = reservationService.reserveSeat(
                    new ReserveSeatRequest(100L, "12A", 77L, null, null, null, null));

            assertThat(inventory.getAvailableSeats()).isEqualTo(2);
            assertThat(inventory.getReservedSeats()).isEqualTo(1);
            assertThat(response.originatingHoldId()).isNull();
            assertThat(inventory.getHistory())
                    .anySatisfy(entry -> assertThat(entry.getHistoryType()).isEqualTo(InventoryHistoryType.SEAT_RESERVED));
        }

        @Test
        void lastSeatDirectReservationFlipsSoldOut() {
            inventory.setAvailableSeats(1);
            inventory.setReservedSeats(2);
            stubNoExistingReservation();
            stubSaveEcho();
            when(seatHoldRepository.findByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    10L, 2L, SeatHoldStatus.ACTIVE)).thenReturn(Optional.empty());

            reservationService.reserveSeat(new ReserveSeatRequest(100L, "12A", 77L, null, null, null, null));

            assertThat(inventory.getStatus()).isEqualTo(InventoryStatus.SOLD_OUT);
        }

        @Test
        void alreadyReservedSeatThrows() {
            when(seatReservationRepository.existsByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    10L, 2L, SeatReservationStatus.RESERVED)).thenReturn(true);

            assertThatThrownBy(() -> reservationService.reserveSeat(
                    new ReserveSeatRequest(100L, "12A", 77L, null, null, null, null)))
                    .isInstanceOf(SeatAlreadyReservedException.class);
        }

        @Test
        void closedInventoryRejectsDirectReservation() {
            inventory.setStatus(InventoryStatus.CLOSED);
            stubNoExistingReservation();
            when(seatHoldRepository.findByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    10L, 2L, SeatHoldStatus.ACTIVE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> reservationService.reserveSeat(
                    new ReserveSeatRequest(100L, "12A", 77L, null, null, null, null)))
                    .isInstanceOf(IllegalStateException.class);
        }

        // §9 contained-v1 check-in rule: the direct path enforces cabin +
        // entitlement ceiling when the caller supplies them; booking
        // confirmation (hold-based, fields omitted) is untouched.

        @Test
        void checkInCrossCabinMoveIsRejected() {
            stubNoExistingReservation();
            when(seatHoldRepository.findByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    10L, 2L, SeatHoldStatus.ACTIVE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> reservationService.reserveSeat(new ReserveSeatRequest(
                    100L, "12A", 77L, 200L, null, SeatType.BUSINESS, new BigDecimal("100.00"))))
                    .isInstanceOf(SeatCabinMismatchException.class);
        }

        @Test
        void checkInSeatAboveEntitlementIsRejected() {
            // 12A is a front-of-cabin WINDOW (lists max(15, 12) = 15.00, §4);
            // a passenger who paid 0 can't take it.
            stubNoExistingReservation();
            when(seatHoldRepository.findByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    10L, 2L, SeatHoldStatus.ACTIVE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> reservationService.reserveSeat(new ReserveSeatRequest(
                    100L, "12A", 77L, 200L, null, SeatType.ECONOMY, BigDecimal.ZERO)))
                    .isInstanceOf(InventoryConflictException.class)
                    .hasMessageContaining("exceeds")
                    .hasMessageContaining("Manage-Booking");

            // Nothing mutated on rejection.
            assertThat(inventory.getAvailableSeats()).isEqualTo(3);
        }

        @Test
        void checkInSeatAtTheCeilingIsAllowed() {
            // Paid 15.00 at booking -> this 15.00-listed seat is a free change
            // (downgrade/equal allowed, no refund - §9).
            stubNoExistingReservation();
            stubSaveEcho();
            when(seatHoldRepository.findByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    10L, 2L, SeatHoldStatus.ACTIVE)).thenReturn(Optional.empty());

            SeatReservationResponse response = reservationService.reserveSeat(new ReserveSeatRequest(
                    100L, "12A", 77L, 200L, null, SeatType.ECONOMY, new BigDecimal("15.00")));

            assertThat(response.seatNumber()).isEqualTo("12A");
            assertThat(inventory.getReservedSeats()).isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------
    // cancelReservation
    // ---------------------------------------------------------------

    @Nested
    class CancelReservation {

        private SeatReservation reservedSeat(Long bookingId) {
            return SeatReservation.builder()
                    .id(9L).flightInventory(inventory).aircraftSeat(seat).bookingId(bookingId)
                    .status(SeatReservationStatus.RESERVED).reservedAt(LocalDateTime.now())
                    .build();
        }

        @Test
        void cancelReturnsSeatToPoolAndReopensSoldOut() {
            inventory.setStatus(InventoryStatus.SOLD_OUT);
            inventory.setAvailableSeats(0);
            inventory.setReservedSeats(3);
            SeatReservation reservation = reservedSeat(77L);
            when(seatReservationRepository.findByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    10L, 2L, SeatReservationStatus.RESERVED)).thenReturn(Optional.of(reservation));

            SeatReservationResponse response = reservationService.cancelReservation(
                    new ReleaseSeatRequest(100L, "12A", 77L, "booking cancelled"));

            assertThat(response.status()).isEqualTo(SeatReservationStatus.CANCELLED);
            assertThat(reservation.getCancelledAt()).isNotNull();
            assertThat(inventory.getAvailableSeats()).isEqualTo(1);
            assertThat(inventory.getReservedSeats()).isEqualTo(2);
            assertThat(inventory.getStatus()).isEqualTo(InventoryStatus.OPEN);
        }

        @Test
        void cancelBySomeoneElsesBookingThrows() {
            when(seatReservationRepository.findByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    10L, 2L, SeatReservationStatus.RESERVED)).thenReturn(Optional.of(reservedSeat(88L)));

            assertThatThrownBy(() -> reservationService.cancelReservation(
                    new ReleaseSeatRequest(100L, "12A", 77L, null)))
                    .isInstanceOf(InventoryConflictException.class)
                    .hasMessageContaining("different booking");
        }

        @Test
        void cancelWithoutActiveReservationThrows() {
            when(seatReservationRepository.findByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    10L, 2L, SeatReservationStatus.RESERVED)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> reservationService.cancelReservation(
                    new ReleaseSeatRequest(100L, "12A", 77L, null)))
                    .isInstanceOf(InventoryConflictException.class)
                    .hasMessageContaining("No active reservation");
        }
    }
}
