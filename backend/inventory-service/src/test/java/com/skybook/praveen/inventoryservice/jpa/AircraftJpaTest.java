package com.skybook.praveen.inventoryservice.jpa;

import com.skybook.praveen.inventoryservice.entity.Aircraft;
import com.skybook.praveen.inventoryservice.enums.AircraftStatus;
import com.skybook.praveen.inventoryservice.repository.AircraftRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AircraftJpaTest extends AbstractPostgresJpaTest {

    @Autowired
    private AircraftRepository aircraftRepository;

    @Autowired
    private EntityManager entityManager;

    private Aircraft aircraft(String registration) {
        return Aircraft.builder()
                .registrationNumber(registration)
                .manufacturer("Airbus")
                .model("A320neo")
                .build();
    }

    // ---------------------------------------------------------------
    // Entity rules
    // ---------------------------------------------------------------

    @Test
    void registrationNumberIsUnique() {
        aircraftRepository.saveAndFlush(aircraft("VT-SKB"));

        assertThatThrownBy(() -> aircraftRepository.saveAndFlush(aircraft("VT-SKB")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void statusDefaultsToActiveAndTotalSeatsToZero() {
        Aircraft saved = aircraftRepository.saveAndFlush(aircraft("VT-DEF"));

        assertThat(saved.getStatus()).isEqualTo(AircraftStatus.ACTIVE);
        assertThat(saved.getTotalSeats()).isZero();
    }

    // One violation per test: PostgreSQL aborts the transaction after the
    // first constraint failure, so a second flush in the same test can't run.

    @Test
    void manufacturerIsMandatory() {
        Aircraft noManufacturer = aircraft("VT-NM1");
        noManufacturer.setManufacturer(null);
        assertThatThrownBy(() -> aircraftRepository.saveAndFlush(noManufacturer))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void modelIsMandatory() {
        Aircraft noModel = aircraft("VT-NM2");
        noModel.setModel(null);
        assertThatThrownBy(() -> aircraftRepository.saveAndFlush(noModel))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void auditColumnsArePopulated() {
        Aircraft saved = aircraftRepository.saveAndFlush(aircraft("VT-AUD"));

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getCreatedBy()).isEqualTo("system");
        assertThat(saved.getVersion()).isNotNull();
    }

    @Test
    void versionIncrementsOnUpdate() {
        Aircraft saved = aircraftRepository.saveAndFlush(aircraft("VT-VER"));
        Long initialVersion = saved.getVersion();

        saved.setStatus(AircraftStatus.MAINTENANCE);
        Aircraft updated = aircraftRepository.saveAndFlush(saved);

        assertThat(updated.getVersion()).isEqualTo(initialVersion + 1);
    }

    @Test
    void statusIsStoredAsString() {
        Aircraft saved = aircraftRepository.saveAndFlush(aircraft("VT-STR"));

        String stored = (String) entityManager.createNativeQuery(
                        "select status from aircraft where id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();

        assertThat(stored).isEqualTo("ACTIVE");
    }

    // ---------------------------------------------------------------
    // Repository rules
    // ---------------------------------------------------------------

    @Test
    void findByRegistrationNumberAndExists() {
        aircraftRepository.saveAndFlush(aircraft("VT-FND"));

        assertThat(aircraftRepository.findByRegistrationNumber("VT-FND")).isPresent();
        assertThat(aircraftRepository.findByRegistrationNumber("VT-NOPE")).isEmpty();
        assertThat(aircraftRepository.existsByRegistrationNumber("VT-FND")).isTrue();
        assertThat(aircraftRepository.existsByRegistrationNumber("VT-NOPE")).isFalse();
    }

    @Test
    void findByStatusFilters() {
        aircraftRepository.saveAndFlush(aircraft("VT-AC1"));
        Aircraft grounded = aircraft("VT-AC2");
        grounded.setStatus(AircraftStatus.GROUNDED);
        aircraftRepository.saveAndFlush(grounded);

        assertThat(aircraftRepository.findByStatus(AircraftStatus.GROUNDED))
                .extracting(Aircraft::getRegistrationNumber)
                .containsExactly("VT-AC2");
    }
}
