package com.skybook.praveen.inventoryservice.domain;

import com.skybook.praveen.inventoryservice.dto.request.CreateAircraftSeatRequest;
import com.skybook.praveen.inventoryservice.entity.Aircraft;
import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import com.skybook.praveen.inventoryservice.enums.SeatPosition;
import com.skybook.praveen.inventoryservice.enums.SeatType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatMapGeneratorTest {

    private final SeatMapGenerator generator = new SeatMapGenerator();

    private Aircraft emptyAircraft() {
        Aircraft aircraft = Aircraft.builder()
                .id(1L)
                .registrationNumber("VT-SKB")
                .totalSeats(0)
                .build();
        aircraft.setSeats(new ArrayList<>());
        return aircraft;
    }

    private CreateAircraftSeatRequest seatRequest(String seatNumber, int row) {
        return new CreateAircraftSeatRequest(seatNumber, row, SeatType.ECONOMY, SeatPosition.WINDOW, null);
    }

    @Test
    void generatesSeatsAttachedToTheAircraft() {
        Aircraft aircraft = emptyAircraft();

        List<AircraftSeat> created = generator.generate(aircraft,
                List.of(seatRequest("12A", 12), seatRequest("12B", 12)));

        assertThat(created).hasSize(2);
        assertThat(created).allSatisfy(seat -> assertThat(seat.getAircraft()).isSameAs(aircraft));
        assertThat(aircraft.getSeats()).containsExactlyElementsOf(created);
        assertThat(aircraft.getTotalSeats()).isEqualTo(2);
    }

    @Test
    void mapsAllRequestFields() {
        Aircraft aircraft = emptyAircraft();
        CreateAircraftSeatRequest request = new CreateAircraftSeatRequest(
                "14C", 14, SeatType.BUSINESS, SeatPosition.AISLE, true);

        AircraftSeat seat = generator.generate(aircraft, List.of(request)).getFirst();

        assertThat(seat.getSeatNumber()).isEqualTo("14C");
        assertThat(seat.getRowNumber()).isEqualTo(14);
        assertThat(seat.getSeatType()).isEqualTo(SeatType.BUSINESS);
        assertThat(seat.getPosition()).isEqualTo(SeatPosition.AISLE);
        assertThat(seat.getExitRow()).isTrue();
    }

    @Test
    void nullExitRowDefaultsToFalse() {
        AircraftSeat seat = generator.generate(emptyAircraft(), List.of(seatRequest("12A", 12))).getFirst();

        assertThat(seat.getExitRow()).isFalse();
    }

    @Test
    void duplicateSeatNumberWithinRequestThrows() {
        assertThatThrownBy(() -> generator.generate(emptyAircraft(),
                List.of(seatRequest("12A", 12), seatRequest("12A", 12))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("12A");
    }

    @Test
    void collisionWithExistingSeatThrowsAndAddsNothing() {
        Aircraft aircraft = emptyAircraft();
        generator.generate(aircraft, List.of(seatRequest("12A", 12)));

        assertThatThrownBy(() -> generator.generate(aircraft,
                List.of(seatRequest("12B", 12), seatRequest("12A", 12))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");

        // toList() materializes before addAll, so a failed batch adds nothing.
        assertThat(aircraft.getSeats()).hasSize(1);
        assertThat(aircraft.getTotalSeats()).isEqualTo(1);
    }

    @Test
    void secondBatchAppendsAndRecountsTotal() {
        Aircraft aircraft = emptyAircraft();
        generator.generate(aircraft, List.of(seatRequest("12A", 12)));
        generator.generate(aircraft, List.of(seatRequest("12B", 12), seatRequest("12C", 12)));

        assertThat(aircraft.getSeats()).hasSize(3);
        assertThat(aircraft.getTotalSeats()).isEqualTo(3);
    }
}
