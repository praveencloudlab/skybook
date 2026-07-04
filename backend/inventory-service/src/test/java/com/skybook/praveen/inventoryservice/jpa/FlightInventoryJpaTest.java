package com.skybook.praveen.inventoryservice.jpa;

import com.skybook.praveen.inventoryservice.entity.Aircraft;
import com.skybook.praveen.inventoryservice.entity.FlightInventory;
import com.skybook.praveen.inventoryservice.entity.InventoryHistory;
import com.skybook.praveen.inventoryservice.enums.InventoryHistoryType;
import com.skybook.praveen.inventoryservice.enums.InventoryStatus;
import com.skybook.praveen.inventoryservice.repository.AircraftRepository;
import com.skybook.praveen.inventoryservice.repository.FlightInventoryRepository;
import com.skybook.praveen.inventoryservice.repository.InventoryHistoryRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlightInventoryJpaTest extends AbstractPostgresJpaTest {

    @Autowired
    private FlightInventoryRepository flightInventoryRepository;

    @Autowired
    private InventoryHistoryRepository inventoryHistoryRepository;

    @Autowired
    private AircraftRepository aircraftRepository;

    @Autowired
    private EntityManager entityManager;

    private Aircraft aircraft;

    @BeforeEach
    void setUp() {
        inventoryHistoryRepository.deleteAll();
        flightInventoryRepository.deleteAll();
        aircraftRepository.deleteAll();
        aircraft = aircraftRepository.saveAndFlush(Aircraft.builder()
                .registrationNumber("VT-INV").manufacturer("Airbus").model("A320neo").totalSeats(3).build());
    }

    private FlightInventory inventory(Long flightId) {
        return FlightInventory.builder()
                .flightId(flightId)
                .aircraft(aircraft)
                .totalSeats(3)
                .availableSeats(3)
                .heldSeats(0)
                .reservedSeats(0)
                .blockedSeats(0)
                .build();
    }

    // ---------------------------------------------------------------
    // Entity rules
    // ---------------------------------------------------------------

    @Test
    void oneInventoryPerFlightId() {
        flightInventoryRepository.saveAndFlush(inventory(100L));

        assertThatThrownBy(() -> flightInventoryRepository.saveAndFlush(inventory(100L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // One violation per test: PostgreSQL aborts the transaction after the
    // first constraint failure, so a second flush in the same test can't run.

    @Test
    void flightIdIsMandatory() {
        FlightInventory noFlight = inventory(null);
        assertThatThrownBy(() -> flightInventoryRepository.saveAndFlush(noFlight))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aircraftIsMandatory() {
        FlightInventory noAircraft = inventory(101L);
        noAircraft.setAircraft(null);
        assertThatThrownBy(() -> flightInventoryRepository.saveAndFlush(noAircraft))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void statusDefaultsToOpenAndCountsDefaultViaPrePersist() {
        FlightInventory bare = FlightInventory.builder()
                .flightId(102L)
                .aircraft(aircraft)
                .totalSeats(3)
                .blockedSeats(1)
                .build();

        FlightInventory saved = flightInventoryRepository.saveAndFlush(bare);

        assertThat(saved.getStatus()).isEqualTo(InventoryStatus.OPEN);
        assertThat(saved.getAvailableSeats()).isEqualTo(2); // total - blocked
        assertThat(saved.getHeldSeats()).isZero();
        assertThat(saved.getReservedSeats()).isZero();
    }

    @Test
    void statusIsStoredAsString() {
        FlightInventory saved = flightInventoryRepository.saveAndFlush(inventory(103L));

        String stored = (String) entityManager.createNativeQuery(
                        "select status from flight_inventory where id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();

        assertThat(stored).isEqualTo("OPEN");
    }

    @Test
    void versionIncrementsOnCountUpdate() {
        FlightInventory saved = flightInventoryRepository.saveAndFlush(inventory(104L));
        Long initialVersion = saved.getVersion();

        saved.setAvailableSeats(2);
        saved.setHeldSeats(1);
        FlightInventory updated = flightInventoryRepository.saveAndFlush(saved);

        assertThat(updated.getVersion()).isEqualTo(initialVersion + 1);
    }

    // ---------------------------------------------------------------
    // InventoryHistory (cascade child)
    // ---------------------------------------------------------------

    @Test
    void historyPersistsViaCascadeAndReadsBackInChangedAtOrder() {
        FlightInventory saved = flightInventoryRepository.saveAndFlush(inventory(105L));

        saved.getHistory().add(InventoryHistory.builder()
                .flightInventory(saved).historyType(InventoryHistoryType.SEAT_HELD)
                .bookingId(42L).details("second")
                .changedAt(LocalDateTime.now().plusSeconds(1)).build());
        saved.getHistory().add(InventoryHistory.builder()
                .flightInventory(saved).historyType(InventoryHistoryType.INVENTORY_CREATED)
                .details("first")
                .changedAt(LocalDateTime.now().minusSeconds(1)).build());
        flightInventoryRepository.saveAndFlush(saved);

        assertThat(inventoryHistoryRepository.findByFlightInventoryIdOrderByChangedAtAsc(saved.getId()))
                .extracting(InventoryHistory::getDetails)
                .containsExactly("first", "second");
    }

    @Test
    void historyTypeIsStoredAsString() {
        FlightInventory saved = flightInventoryRepository.saveAndFlush(inventory(106L));
        saved.getHistory().add(InventoryHistory.builder()
                .flightInventory(saved).historyType(InventoryHistoryType.INVENTORY_CREATED)
                .changedAt(LocalDateTime.now()).build());
        flightInventoryRepository.saveAndFlush(saved);

        String stored = (String) entityManager.createNativeQuery(
                        "select history_type from inventory_history limit 1")
                .getSingleResult();

        assertThat(stored).isEqualTo("INVENTORY_CREATED");
    }

    @Test
    void historyFindByBookingId() {
        FlightInventory saved = flightInventoryRepository.saveAndFlush(inventory(107L));
        saved.getHistory().add(InventoryHistory.builder()
                .flightInventory(saved).historyType(InventoryHistoryType.SEAT_HELD)
                .bookingId(42L).changedAt(LocalDateTime.now()).build());
        flightInventoryRepository.saveAndFlush(saved);

        assertThat(inventoryHistoryRepository.findByBookingId(42L)).hasSize(1);
        assertThat(inventoryHistoryRepository.findByBookingId(999L)).isEmpty();
    }

    // ---------------------------------------------------------------
    // Repository rules
    // ---------------------------------------------------------------

    @Test
    void findByFlightIdAndExists() {
        flightInventoryRepository.saveAndFlush(inventory(108L));

        assertThat(flightInventoryRepository.findByFlightId(108L)).isPresent();
        assertThat(flightInventoryRepository.existsByFlightId(108L)).isTrue();
        assertThat(flightInventoryRepository.existsByFlightId(999L)).isFalse();
    }

    @Test
    void findByStatusAndByAircraftId() {
        flightInventoryRepository.saveAndFlush(inventory(109L));
        FlightInventory closed = inventory(110L);
        closed.setStatus(InventoryStatus.CLOSED);
        flightInventoryRepository.saveAndFlush(closed);

        assertThat(flightInventoryRepository.findByStatus(InventoryStatus.CLOSED))
                .extracting(FlightInventory::getFlightId)
                .containsExactly(110L);
        assertThat(flightInventoryRepository.findByAircraftId(aircraft.getId())).hasSize(2);
    }
}
