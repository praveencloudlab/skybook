package com.skybook.praveen.inventoryservice.jpa;

import com.skybook.praveen.inventoryservice.entity.Aircraft;
import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import com.skybook.praveen.inventoryservice.enums.AircraftSeatStatus;
import com.skybook.praveen.inventoryservice.enums.SeatPosition;
import com.skybook.praveen.inventoryservice.enums.SeatType;
import com.skybook.praveen.inventoryservice.repository.AircraftRepository;
import com.skybook.praveen.inventoryservice.repository.AircraftSeatRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AircraftSeatJpaTest extends AbstractPostgresJpaTest {

    @Autowired
    private AircraftRepository aircraftRepository;

    @Autowired
    private AircraftSeatRepository aircraftSeatRepository;

    @Autowired
    private EntityManager entityManager;

    private Aircraft aircraft;

    @BeforeEach
    void setUp() {
        aircraftSeatRepository.deleteAll();
        aircraftRepository.deleteAll();
        aircraft = aircraftRepository.saveAndFlush(Aircraft.builder()
                .registrationNumber("VT-SEA")
                .manufacturer("Airbus")
                .model("A320neo")
                .build());
    }

    private AircraftSeat seat(Aircraft owner, String seatNumber, int row) {
        return AircraftSeat.builder()
                .aircraft(owner)
                .seatNumber(seatNumber)
                .rowNumber(row)
                .seatType(SeatType.ECONOMY)
                .position(SeatPosition.WINDOW)
                .build();
    }

    // ---------------------------------------------------------------
    // Entity rules
    // ---------------------------------------------------------------

    @Test
    void seatNumberIsUniquePerAircraft() {
        aircraftSeatRepository.saveAndFlush(seat(aircraft, "12A", 12));

        assertThatThrownBy(() -> aircraftSeatRepository.saveAndFlush(seat(aircraft, "12A", 12)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameSeatNumberAllowedOnDifferentAircraft() {
        Aircraft other = aircraftRepository.saveAndFlush(Aircraft.builder()
                .registrationNumber("VT-OTH").manufacturer("Boeing").model("737 MAX 8").build());

        aircraftSeatRepository.saveAndFlush(seat(aircraft, "12A", 12));
        AircraftSeat second = aircraftSeatRepository.saveAndFlush(seat(other, "12A", 12));

        assertThat(second.getId()).isNotNull();
    }

    @Test
    void seatBelongsToItsAircraft() {
        AircraftSeat saved = aircraftSeatRepository.saveAndFlush(seat(aircraft, "12A", 12));

        assertThat(saved.getAircraft().getId()).isEqualTo(aircraft.getId());
    }

    @Test
    void statusDefaultsToActiveAndExitRowToFalse() {
        AircraftSeat saved = aircraftSeatRepository.saveAndFlush(seat(aircraft, "12A", 12));

        assertThat(saved.getStatus()).isEqualTo(AircraftSeatStatus.ACTIVE);
        assertThat(saved.getExitRow()).isFalse();
    }

    @Test
    void enumsAreStoredAsStringsNotOrdinals() {
        AircraftSeat business = AircraftSeat.builder()
                .aircraft(aircraft).seatNumber("1A").rowNumber(1)
                .seatType(SeatType.BUSINESS).position(SeatPosition.AISLE)
                .status(AircraftSeatStatus.BLOCKED).exitRow(true)
                .build();
        AircraftSeat saved = aircraftSeatRepository.saveAndFlush(business);

        Object[] row = (Object[]) entityManager.createNativeQuery(
                        "select seat_type, position, status from aircraft_seats where id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();

        assertThat(row[0]).isEqualTo("BUSINESS");
        assertThat(row[1]).isEqualTo("AISLE");
        assertThat(row[2]).isEqualTo("BLOCKED");
    }

    @Test
    void inoperativeSeatPersistsCorrectly() {
        AircraftSeat broken = seat(aircraft, "31F", 31);
        broken.setStatus(AircraftSeatStatus.INOPERATIVE);

        AircraftSeat saved = aircraftSeatRepository.saveAndFlush(broken);

        assertThat(aircraftSeatRepository.findById(saved.getId()))
                .hasValueSatisfying(found ->
                        assertThat(found.getStatus()).isEqualTo(AircraftSeatStatus.INOPERATIVE));
    }

    @Test
    void deletingAircraftCascadesToSeats() {
        aircraft.getSeats().add(seat(aircraft, "12A", 12));
        aircraft.getSeats().add(seat(aircraft, "12B", 12));
        aircraftRepository.saveAndFlush(aircraft);
        assertThat(aircraftSeatRepository.count()).isEqualTo(2);

        aircraftRepository.delete(aircraft);
        aircraftRepository.flush();

        assertThat(aircraftSeatRepository.count()).isZero();
    }

    @Test
    void removingSeatFromCollectionOrphanRemovesIt() {
        aircraft.getSeats().add(seat(aircraft, "12A", 12));
        aircraft.getSeats().add(seat(aircraft, "12B", 12));
        aircraftRepository.saveAndFlush(aircraft);

        aircraft.getSeats().removeFirst();
        aircraftRepository.saveAndFlush(aircraft);

        assertThat(aircraftSeatRepository.count()).isEqualTo(1);
    }

    // ---------------------------------------------------------------
    // Repository rules
    // ---------------------------------------------------------------

    @Test
    void findByAircraftIdOrdersByRowThenSeat() {
        aircraftSeatRepository.saveAndFlush(seat(aircraft, "12B", 12));
        aircraftSeatRepository.saveAndFlush(seat(aircraft, "2A", 2));
        aircraftSeatRepository.saveAndFlush(seat(aircraft, "12A", 12));

        assertThat(aircraftSeatRepository.findByAircraftIdOrderByRowNumberAscSeatNumberAsc(aircraft.getId()))
                .extracting(AircraftSeat::getSeatNumber)
                .containsExactly("2A", "12A", "12B");
    }

    @Test
    void findByAircraftIdAndSeatNumber() {
        aircraftSeatRepository.saveAndFlush(seat(aircraft, "12A", 12));

        assertThat(aircraftSeatRepository.findByAircraftIdAndSeatNumber(aircraft.getId(), "12A")).isPresent();
        assertThat(aircraftSeatRepository.findByAircraftIdAndSeatNumber(aircraft.getId(), "99Z")).isEmpty();
    }

    @Test
    void findAndCountByStatus() {
        aircraftSeatRepository.saveAndFlush(seat(aircraft, "12A", 12));
        AircraftSeat blocked = seat(aircraft, "12B", 12);
        blocked.setStatus(AircraftSeatStatus.BLOCKED);
        aircraftSeatRepository.saveAndFlush(blocked);

        assertThat(aircraftSeatRepository.findByAircraftIdAndStatus(aircraft.getId(), AircraftSeatStatus.ACTIVE))
                .hasSize(1);
        assertThat(aircraftSeatRepository.countByAircraftIdAndStatus(aircraft.getId(), AircraftSeatStatus.ACTIVE))
                .isEqualTo(1);
        assertThat(aircraftSeatRepository.countByAircraftIdAndStatus(aircraft.getId(), AircraftSeatStatus.BLOCKED))
                .isEqualTo(1);
    }
}
