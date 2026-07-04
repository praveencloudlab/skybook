package com.skybook.praveen.inventoryservice.jpa;

import com.skybook.praveen.inventoryservice.entity.Aircraft;
import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import com.skybook.praveen.inventoryservice.entity.FlightInventory;
import com.skybook.praveen.inventoryservice.entity.SeatHold;
import com.skybook.praveen.inventoryservice.entity.SeatReservation;
import com.skybook.praveen.inventoryservice.enums.SeatHoldStatus;
import com.skybook.praveen.inventoryservice.enums.SeatPosition;
import com.skybook.praveen.inventoryservice.enums.SeatReservationStatus;
import com.skybook.praveen.inventoryservice.enums.SeatType;
import com.skybook.praveen.inventoryservice.repository.AircraftRepository;
import com.skybook.praveen.inventoryservice.repository.AircraftSeatRepository;
import com.skybook.praveen.inventoryservice.repository.FlightInventoryRepository;
import com.skybook.praveen.inventoryservice.repository.SeatHoldRepository;
import com.skybook.praveen.inventoryservice.repository.SeatReservationRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatHoldAndReservationJpaTest extends AbstractPostgresJpaTest {

    @Autowired
    private SeatHoldRepository seatHoldRepository;
    @Autowired
    private SeatReservationRepository seatReservationRepository;
    @Autowired
    private FlightInventoryRepository flightInventoryRepository;
    @Autowired
    private AircraftSeatRepository aircraftSeatRepository;
    @Autowired
    private AircraftRepository aircraftRepository;
    @Autowired
    private EntityManager entityManager;

    private FlightInventory inventory;
    private AircraftSeat seat;

    @BeforeEach
    void setUp() {
        seatReservationRepository.deleteAll();
        seatHoldRepository.deleteAll();
        flightInventoryRepository.deleteAll();
        aircraftSeatRepository.deleteAll();
        aircraftRepository.deleteAll();

        Aircraft aircraft = aircraftRepository.saveAndFlush(Aircraft.builder()
                .registrationNumber("VT-HLD").manufacturer("Airbus").model("A320neo").totalSeats(1).build());
        seat = aircraftSeatRepository.saveAndFlush(AircraftSeat.builder()
                .aircraft(aircraft).seatNumber("12A").rowNumber(12)
                .seatType(SeatType.ECONOMY).position(SeatPosition.WINDOW).build());
        inventory = flightInventoryRepository.saveAndFlush(FlightInventory.builder()
                .flightId(100L).aircraft(aircraft)
                .totalSeats(1).availableSeats(1).heldSeats(0).reservedSeats(0).blockedSeats(0)
                .build());
    }

    private SeatHold.SeatHoldBuilder holdBuilder() {
        return SeatHold.builder()
                .flightInventory(inventory)
                .aircraftSeat(seat)
                .bookingId(42L)
                .expiresAt(LocalDateTime.now().plusMinutes(15));
    }

    @Nested
    class Holds {

        @Test
        void holdLinksToInventoryAndSeat() {
            SeatHold saved = seatHoldRepository.saveAndFlush(holdBuilder().build());

            assertThat(saved.getFlightInventory().getId()).isEqualTo(inventory.getId());
            assertThat(saved.getAircraftSeat().getId()).isEqualTo(seat.getId());
        }

        // One violation per test: PostgreSQL aborts the transaction after the
        // first constraint failure, so a second flush in the same test can't run.

        @Test
        void bookingIdIsMandatory() {
            assertThatThrownBy(() -> seatHoldRepository.saveAndFlush(holdBuilder().bookingId(null).build()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void expiresAtIsMandatory() {
            assertThatThrownBy(() -> seatHoldRepository.saveAndFlush(holdBuilder().expiresAt(null).build()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void statusDefaultsToActiveAndHeldAtDefaultsToNow() {
            SeatHold saved = seatHoldRepository.saveAndFlush(holdBuilder().build());

            assertThat(saved.getStatus()).isEqualTo(SeatHoldStatus.ACTIVE);
            assertThat(saved.getHeldAt()).isNotNull();
        }

        @Test
        void statusIsStoredAsString() {
            SeatHold saved = seatHoldRepository.saveAndFlush(holdBuilder().build());

            String stored = (String) entityManager.createNativeQuery(
                            "select status from seat_holds where id = :id")
                    .setParameter("id", saved.getId())
                    .getSingleResult();

            assertThat(stored).isEqualTo("ACTIVE");
        }

        @Test
        void terminalAndActiveHoldsCanCoexistOnTheSameSeat() {
            // Documents the deliberate absence of a DB uniqueness rule:
            // "one ACTIVE hold per seat" is enforced in the service layer
            // (see INVENTORY_SERVICE_MODULE.md section 13 for the partial-index option).
            SeatHold released = holdBuilder().build();
            released.setStatus(SeatHoldStatus.RELEASED);
            seatHoldRepository.saveAndFlush(released);

            SeatHold active = seatHoldRepository.saveAndFlush(holdBuilder().build());

            assertThat(active.getId()).isNotNull();
            assertThat(seatHoldRepository.count()).isEqualTo(2);
        }

        @Test
        void findByBookingIdAndByStatusFinders() {
            seatHoldRepository.saveAndFlush(holdBuilder().build());

            assertThat(seatHoldRepository.findByBookingId(42L)).hasSize(1);
            assertThat(seatHoldRepository.findByBookingIdAndStatus(42L, SeatHoldStatus.ACTIVE)).hasSize(1);
            assertThat(seatHoldRepository.findByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    inventory.getId(), seat.getId(), SeatHoldStatus.ACTIVE)).isPresent();
            assertThat(seatHoldRepository.existsByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    inventory.getId(), seat.getId(), SeatHoldStatus.ACTIVE)).isTrue();
        }

        @Test
        void expirySweepFinderReturnsOnlyOverdueActiveHolds() {
            SeatHold overdue = holdBuilder().expiresAt(LocalDateTime.now().minusMinutes(1)).build();
            seatHoldRepository.saveAndFlush(overdue);

            SeatHold fresh = holdBuilder().build();
            seatHoldRepository.saveAndFlush(fresh);

            SeatHold overdueButReleased = holdBuilder().expiresAt(LocalDateTime.now().minusMinutes(1)).build();
            overdueButReleased.setStatus(SeatHoldStatus.RELEASED);
            seatHoldRepository.saveAndFlush(overdueButReleased);

            assertThat(seatHoldRepository.findByStatusAndExpiresAtBefore(
                    SeatHoldStatus.ACTIVE, LocalDateTime.now()))
                    .containsExactly(overdue);
        }
    }

    @Nested
    class Reservations {

        private SeatReservation.SeatReservationBuilder reservationBuilder() {
            return SeatReservation.builder()
                    .flightInventory(inventory)
                    .aircraftSeat(seat)
                    .bookingId(42L);
        }

        @Test
        void reservationLinksAndDefaults() {
            SeatReservation saved = seatReservationRepository.saveAndFlush(reservationBuilder().build());

            assertThat(saved.getFlightInventory().getId()).isEqualTo(inventory.getId());
            assertThat(saved.getStatus()).isEqualTo(SeatReservationStatus.RESERVED);
            assertThat(saved.getReservedAt()).isNotNull();
            assertThat(saved.getCancelledAt()).isNull();
        }

        @Test
        void bookingIdIsMandatory() {
            assertThatThrownBy(() -> seatReservationRepository.saveAndFlush(
                    reservationBuilder().bookingId(null).build()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void passengerIsOptional() {
            SeatReservation withoutPassenger = seatReservationRepository.saveAndFlush(
                    reservationBuilder().bookingPassengerId(null).build());

            assertThat(withoutPassenger.getId()).isNotNull();
        }

        @Test
        void statusIsStoredAsString() {
            SeatReservation saved = seatReservationRepository.saveAndFlush(reservationBuilder().build());

            String stored = (String) entityManager.createNativeQuery(
                            "select status from seat_reservations where id = :id")
                    .setParameter("id", saved.getId())
                    .getSingleResult();

            assertThat(stored).isEqualTo("RESERVED");
        }

        @Test
        void originatingHoldLinkPersists() {
            SeatHold hold = seatHoldRepository.saveAndFlush(holdBuilder().build());
            hold.setStatus(SeatHoldStatus.CONFIRMED);
            seatHoldRepository.saveAndFlush(hold);

            SeatReservation saved = seatReservationRepository.saveAndFlush(
                    reservationBuilder().originatingHold(hold).build());

            assertThat(seatReservationRepository.findById(saved.getId()))
                    .hasValueSatisfying(found ->
                            assertThat(found.getOriginatingHold().getId()).isEqualTo(hold.getId()));
        }

        @Test
        void repositoryFinders() {
            seatReservationRepository.saveAndFlush(reservationBuilder().build());

            assertThat(seatReservationRepository.findByBookingId(42L)).hasSize(1);
            assertThat(seatReservationRepository.findByFlightInventoryIdAndStatus(
                    inventory.getId(), SeatReservationStatus.RESERVED)).hasSize(1);
            assertThat(seatReservationRepository.existsByFlightInventoryIdAndAircraftSeatIdAndStatus(
                    inventory.getId(), seat.getId(), SeatReservationStatus.RESERVED)).isTrue();
            assertThat(seatReservationRepository.countByFlightInventoryIdAndStatus(
                    inventory.getId(), SeatReservationStatus.RESERVED)).isEqualTo(1);
        }
    }
}
