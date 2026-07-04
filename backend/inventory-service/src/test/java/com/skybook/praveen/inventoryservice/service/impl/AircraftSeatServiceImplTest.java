package com.skybook.praveen.inventoryservice.service.impl;

import com.skybook.praveen.inventoryservice.domain.SeatMapGenerator;
import com.skybook.praveen.inventoryservice.dto.request.CreateAircraftSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.CreateSeatMapRequest;
import com.skybook.praveen.inventoryservice.dto.response.AircraftSeatResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatMapResponse;
import com.skybook.praveen.inventoryservice.entity.Aircraft;
import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import com.skybook.praveen.inventoryservice.enums.AircraftSeatStatus;
import com.skybook.praveen.inventoryservice.enums.SeatPosition;
import com.skybook.praveen.inventoryservice.enums.SeatType;
import com.skybook.praveen.inventoryservice.exception.AircraftNotFoundException;
import com.skybook.praveen.inventoryservice.exception.AircraftSeatNotFoundException;
import com.skybook.praveen.inventoryservice.repository.AircraftRepository;
import com.skybook.praveen.inventoryservice.repository.AircraftSeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AircraftSeatServiceImplTest {

    @Mock
    private AircraftRepository aircraftRepository;

    @Mock
    private AircraftSeatRepository aircraftSeatRepository;

    private AircraftSeatServiceImpl seatService;

    @BeforeEach
    void setUp() {
        // Real generator - it's pure domain logic; only repositories are mocked.
        seatService = new AircraftSeatServiceImpl(
                aircraftRepository, aircraftSeatRepository, new SeatMapGenerator());
    }

    private Aircraft aircraftWithSeats() {
        Aircraft aircraft = Aircraft.builder()
                .id(1L)
                .registrationNumber("VT-SKB")
                .model("A320neo")
                .totalSeats(0)
                .build();
        aircraft.setSeats(new ArrayList<>());
        return aircraft;
    }

    private CreateAircraftSeatRequest seatRequest(String seatNumber, int row) {
        return new CreateAircraftSeatRequest(seatNumber, row, SeatType.ECONOMY, SeatPosition.WINDOW, null);
    }

    @Test
    void addSeatAttachesToAircraftAndUpdatesTotal() {
        Aircraft aircraft = aircraftWithSeats();
        when(aircraftRepository.findById(1L)).thenReturn(Optional.of(aircraft));

        AircraftSeatResponse response = seatService.addSeat(1L, seatRequest("12A", 12));

        assertThat(response.seatNumber()).isEqualTo("12A");
        assertThat(aircraft.getSeats()).hasSize(1);
        assertThat(aircraft.getTotalSeats()).isEqualTo(1);
    }

    @Test
    void addSeatToUnknownAircraftThrows() {
        when(aircraftRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> seatService.addSeat(9L, seatRequest("12A", 12)))
                .isInstanceOf(AircraftNotFoundException.class);
    }

    @Test
    void createSeatMapIsAllOrNothing() {
        Aircraft aircraft = aircraftWithSeats();
        when(aircraftRepository.findById(1L)).thenReturn(Optional.of(aircraft));

        List<AircraftSeatResponse> created = seatService.createSeatMap(1L, new CreateSeatMapRequest(
                List.of(seatRequest("12A", 12), seatRequest("12B", 12), seatRequest("12C", 12))));

        assertThat(created).hasSize(3);
        assertThat(aircraft.getTotalSeats()).isEqualTo(3);

        // A second map containing a collision must add nothing.
        assertThatThrownBy(() -> seatService.createSeatMap(1L, new CreateSeatMapRequest(
                List.of(seatRequest("13A", 13), seatRequest("12A", 12)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(aircraft.getSeats()).hasSize(3);
    }

    @Test
    void seatMapResponseCarriesAircraftContext() {
        Aircraft aircraft = aircraftWithSeats();
        when(aircraftRepository.findById(1L)).thenReturn(Optional.of(aircraft));
        seatService.createSeatMap(1L, new CreateSeatMapRequest(List.of(seatRequest("12A", 12))));

        SeatMapResponse seatMap = seatService.getSeatMap(1L);

        assertThat(seatMap.aircraftId()).isEqualTo(1L);
        assertThat(seatMap.registrationNumber()).isEqualTo("VT-SKB");
        assertThat(seatMap.totalSeats()).isEqualTo(1);
        assertThat(seatMap.seats()).hasSize(1);
    }

    @Test
    void updateSeatStatusChangesTheSeat() {
        Aircraft aircraft = aircraftWithSeats();
        AircraftSeat seat = AircraftSeat.builder()
                .id(7L).aircraft(aircraft).seatNumber("12A").rowNumber(12)
                .seatType(SeatType.ECONOMY).position(SeatPosition.WINDOW)
                .status(AircraftSeatStatus.ACTIVE).exitRow(false)
                .build();
        when(aircraftRepository.findById(1L)).thenReturn(Optional.of(aircraft));
        when(aircraftSeatRepository.findByAircraftIdAndSeatNumber(1L, "12A")).thenReturn(Optional.of(seat));

        AircraftSeatResponse response = seatService.updateSeatStatus(1L, "12A", AircraftSeatStatus.INOPERATIVE);

        assertThat(response.status()).isEqualTo(AircraftSeatStatus.INOPERATIVE);
        assertThat(seat.getStatus()).isEqualTo(AircraftSeatStatus.INOPERATIVE);
    }

    @Test
    void updateUnknownSeatThrows() {
        Aircraft aircraft = aircraftWithSeats();
        when(aircraftRepository.findById(1L)).thenReturn(Optional.of(aircraft));
        when(aircraftSeatRepository.findByAircraftIdAndSeatNumber(1L, "99Z")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> seatService.updateSeatStatus(1L, "99Z", AircraftSeatStatus.BLOCKED))
                .isInstanceOf(AircraftSeatNotFoundException.class);
    }
}
