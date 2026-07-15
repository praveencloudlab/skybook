package com.skybook.praveen.inventoryservice.mapper;

import com.skybook.praveen.inventoryservice.dto.response.AircraftResponse;
import com.skybook.praveen.inventoryservice.dto.response.AircraftSeatResponse;
import com.skybook.praveen.inventoryservice.dto.response.FlightInventoryResponse;
import com.skybook.praveen.inventoryservice.dto.response.InventoryHistoryResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatHoldResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatMapResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatReservationResponse;
import com.skybook.praveen.inventoryservice.entity.Aircraft;
import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import com.skybook.praveen.inventoryservice.entity.FlightInventory;
import com.skybook.praveen.inventoryservice.entity.InventoryHistory;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryMappersTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 7, 3, 12, 0);

    private Aircraft aircraft() {
        Aircraft aircraft = Aircraft.builder()
                .id(1L).registrationNumber("VT-SKB").manufacturer("Airbus").model("A320neo")
                .totalSeats(2).status(AircraftStatus.ACTIVE)
                .build();
        aircraft.setCreatedAt(now);
        aircraft.setUpdatedAt(now);
        aircraft.setCreatedBy("system");
        aircraft.setUpdatedBy("system");
        aircraft.setVersion(0L);
        return aircraft;
    }

    private AircraftSeat seat() {
        return AircraftSeat.builder()
                .id(2L).aircraft(aircraft()).seatNumber("12A").rowNumber(12)
                .seatType(SeatType.ECONOMY).position(SeatPosition.WINDOW)
                .status(AircraftSeatStatus.ACTIVE).exitRow(true)
                .build();
    }

    private FlightInventory inventory() {
        FlightInventory inventory = FlightInventory.builder()
                .id(10L).flightId(100L).aircraft(aircraft())
                .status(InventoryStatus.OPEN)
                .totalSeats(2).availableSeats(1).heldSeats(1).reservedSeats(0).blockedSeats(0)
                .build();
        inventory.setVersion(3L);
        inventory.setCreatedAt(now);
        inventory.setUpdatedAt(now);
        return inventory;
    }

    @Nested
    class AircraftMapping {

        @Test
        void mapsAllFields() {
            AircraftResponse response = AircraftMapper.toResponse(aircraft());

            assertThat(response).isEqualTo(new AircraftResponse(
                    1L, "VT-SKB", "Airbus", "A320neo", 2, AircraftStatus.ACTIVE,
                    "system", "system", 0L, now, now));
        }

        @Test
        void seatMapResponseCarriesOrderedSeats() {
            Aircraft aircraft = aircraft();
            aircraft.getSeats().add(seat());

            // The service prices the seats; the mapper just carries the rows.
            SeatMapResponse response = AircraftMapper.toSeatMapResponse(
                    aircraft, List.of(AircraftSeatMapper.toResponse(seat(), new BigDecimal("30.00"))));

            assertThat(response.aircraftId()).isEqualTo(1L);
            assertThat(response.registrationNumber()).isEqualTo("VT-SKB");
            assertThat(response.aircraftStatus()).isEqualTo(AircraftStatus.ACTIVE);
            assertThat(response.seats()).hasSize(1);
            assertThat(response.seats().getFirst().seatNumber()).isEqualTo("12A");
        }

        @Test
        void emptySeatListMapsToEmptyListNotNull() {
            SeatMapResponse response = AircraftMapper.toSeatMapResponse(aircraft(), List.of());

            assertThat(response.seats()).isNotNull().isEmpty();
        }
    }

    @Test
    void aircraftSeatMapsAllFields() {
        // Exit-row window seat: listed surcharge is the exit-row tier (30.00).
        AircraftSeatResponse response = AircraftSeatMapper.toResponse(seat(), new BigDecimal("30.00"));

        assertThat(response).isEqualTo(new AircraftSeatResponse(
                2L, "12A", 12, SeatType.ECONOMY, SeatPosition.WINDOW, AircraftSeatStatus.ACTIVE,
                true, new BigDecimal("30.00")));
    }

    @Test
    void flightInventoryMapsCountsStatusAndAircraftContext() {
        FlightInventoryResponse response = FlightInventoryMapper.toResponse(inventory());

        assertThat(response.flightId()).isEqualTo(100L);
        assertThat(response.aircraftId()).isEqualTo(1L);
        assertThat(response.aircraftRegistrationNumber()).isEqualTo("VT-SKB");
        assertThat(response.status()).isEqualTo(InventoryStatus.OPEN);
        assertThat(response.totalSeats()).isEqualTo(2);
        assertThat(response.availableSeats()).isEqualTo(1);
        assertThat(response.heldSeats()).isEqualTo(1);
        assertThat(response.reservedSeats()).isZero();
        assertThat(response.blockedSeats()).isZero();
        assertThat(response.version()).isEqualTo(3L);
    }

    @Test
    void seatHoldMapsExpiryAndFlattensSeatAndFlight() {
        SeatHold hold = SeatHold.builder()
                .id(5L).flightInventory(inventory()).aircraftSeat(seat()).bookingId(42L)
                .status(SeatHoldStatus.ACTIVE).heldAt(now).expiresAt(now.plusMinutes(15))
                .build();

        SeatHoldResponse response = SeatHoldMapper.toResponse(hold);

        assertThat(response).isEqualTo(new SeatHoldResponse(
                5L, 100L, 2L, "12A", 42L, SeatHoldStatus.ACTIVE, now, now.plusMinutes(15)));
    }

    @Nested
    class SeatReservationMapping {

        @Test
        void mapsBookingPassengerSeatAndStatus() {
            SeatHold hold = SeatHold.builder().id(5L).build();
            SeatReservation reservation = SeatReservation.builder()
                    .id(9L).flightInventory(inventory()).aircraftSeat(seat())
                    .bookingId(42L).bookingPassengerId(200L).originatingHold(hold)
                    .status(SeatReservationStatus.RESERVED).reservedAt(now)
                    .build();

            SeatReservationResponse response = SeatReservationMapper.toResponse(reservation);

            assertThat(response).isEqualTo(new SeatReservationResponse(
                    9L, 100L, 2L, "12A", 42L, 200L, 5L, SeatReservationStatus.RESERVED, now, null));
        }

        @Test
        void nullOriginatingHoldMapsToNullId() {
            SeatReservation direct = SeatReservation.builder()
                    .id(9L).flightInventory(inventory()).aircraftSeat(seat())
                    .bookingId(42L).status(SeatReservationStatus.RESERVED).reservedAt(now)
                    .build();

            assertThat(SeatReservationMapper.toResponse(direct).originatingHoldId()).isNull();
        }
    }

    @Nested
    class InventoryHistoryMapping {

        @Test
        void mapsSeatLevelEntry() {
            InventoryHistory entry = InventoryHistory.builder()
                    .id(3L).flightInventory(inventory()).historyType(InventoryHistoryType.SEAT_HELD)
                    .aircraftSeat(seat()).bookingId(42L).details("hold placed").changedAt(now)
                    .build();

            InventoryHistoryResponse response = InventoryHistoryMapper.toResponse(entry);

            assertThat(response).isEqualTo(new InventoryHistoryResponse(
                    3L, InventoryHistoryType.SEAT_HELD, "12A", 42L, "hold placed", now));
        }

        @Test
        void inventoryLevelEntryMapsNullSeat() {
            InventoryHistory entry = InventoryHistory.builder()
                    .id(4L).flightInventory(inventory()).historyType(InventoryHistoryType.INVENTORY_CREATED)
                    .details("created").changedAt(now)
                    .build();

            InventoryHistoryResponse response = InventoryHistoryMapper.toResponse(entry);

            assertThat(response.seatNumber()).isNull();
            assertThat(response.bookingId()).isNull();
        }
    }
}
