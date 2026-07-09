package com.skybook.praveen.checkinservice.jpa;

import com.skybook.praveen.checkinservice.entity.CheckIn;
import com.skybook.praveen.checkinservice.entity.CheckInHistory;
import com.skybook.praveen.checkinservice.enums.CheckInHistoryType;
import com.skybook.praveen.checkinservice.enums.CheckInStatus;
import com.skybook.praveen.checkinservice.repository.CheckInHistoryRepository;
import com.skybook.praveen.checkinservice.repository.CheckInRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckInJpaTest extends AbstractPostgresJpaTest {

    @Autowired
    private CheckInRepository checkInRepository;
    @Autowired
    private CheckInHistoryRepository checkInHistoryRepository;

    private long passengerSeq = 1000;
    private static final LocalDateTime DEPARTURE = LocalDateTime.of(2026, 7, 8, 18, 0);

    @BeforeEach
    void cleanUp() {
        checkInRepository.deleteAll();
    }

    private CheckIn.CheckInBuilder checkIn() {
        return CheckIn.builder()
                .status(CheckInStatus.NOT_OPEN)
                .bookingId(42L)
                .bookingReference("SBTEST")
                .bookingPassengerId(++passengerSeq)
                .flightId(7L)
                .flightNumber("BA178")
                .originAirportCode("LHR")
                .destinationAirportCode("JFK")
                .departureTime(DEPARTURE)
                .passengerName("Test Passenger")
                .seatNumber("12B");
    }

    @Test
    void bookingPassengerIdIsUnique() {
        CheckIn first = checkIn().build();
        checkInRepository.saveAndFlush(first);

        CheckIn duplicate = checkIn().bookingPassengerId(first.getBookingPassengerId()).build();

        assertThatThrownBy(() -> checkInRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void prePersistDefaultsStatusWhenNotExplicitlySet() {
        // The service layer sets status explicitly (design doc section 18),
        // but the entity's own @PrePersist backstop must still work for any
        // other write path.
        CheckIn saved = checkInRepository.saveAndFlush(
                CheckIn.builder()
                        .bookingId(42L).bookingReference("SBTEST").bookingPassengerId(++passengerSeq)
                        .flightId(7L).passengerName("Test Passenger")
                        .build());

        assertThat(saved.getStatus()).isEqualTo(CheckInStatus.NOT_OPEN);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getCreatedBy()).isEqualTo("system");
    }

    @Test
    void statusIsStoredAsAString() {
        CheckIn saved = checkInRepository.saveAndFlush(checkIn().build());

        checkInRepository.flush();
        checkInRepository.findById(saved.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(CheckInStatus.NOT_OPEN);
    }

    @Test
    void versionIncrementsOnUpdate() {
        CheckIn saved = checkInRepository.saveAndFlush(checkIn().build());
        Long initialVersion = saved.getVersion();

        saved.setStatus(CheckInStatus.OPEN);
        CheckIn updated = checkInRepository.saveAndFlush(saved);

        assertThat(updated.getVersion()).isEqualTo(initialVersion + 1);
    }

    @Test
    void historyPersistsViaCascadeInChangedAtOrder() {
        CheckIn saved = checkInRepository.saveAndFlush(checkIn().build());

        saved.getHistory().add(CheckInHistory.builder()
                .checkIn(saved).historyType(CheckInHistoryType.CHECKED_IN)
                .actor("USER").source("API").correlationId("req-2").details("second")
                .changedAt(LocalDateTime.now().plusSeconds(1)).build());
        saved.getHistory().add(CheckInHistory.builder()
                .checkIn(saved).historyType(CheckInHistoryType.CHECKIN_CREATED)
                .actor("KAFKA").source("BOOKING_EVENT").correlationId("SBTEST").details("first")
                .changedAt(LocalDateTime.now().minusSeconds(1)).build());
        checkInRepository.saveAndFlush(saved);

        // A dedicated repository query, not saved.getHistory()/a reload's
        // mapped collection - within one persistence context findById
        // returns the same identity-mapped object whose collection just
        // reflects insertion order, not @OrderBy (that only applies when
        // Hibernate actually issues a fresh SELECT), same lesson
        // PaymentHistoryRepository's own JPA test already encodes.
        var entries = checkInHistoryRepository.findByCheckInIdOrderByChangedAtAsc(saved.getId());
        assertThat(entries).extracting(CheckInHistory::getDetails).containsExactly("first", "second");
        assertThat(entries.getFirst().getActor()).isEqualTo("KAFKA");
        assertThat(entries.getFirst().getSource()).isEqualTo("BOOKING_EVENT");
    }

    @Test
    void findersByBookingAndFlightId() {
        CheckIn saved = checkInRepository.saveAndFlush(checkIn().build());

        assertThat(checkInRepository.findByBookingPassengerId(saved.getBookingPassengerId())).isPresent();
        assertThat(checkInRepository.existsByBookingPassengerId(saved.getBookingPassengerId())).isTrue();
        assertThat(checkInRepository.findByBookingId(42L)).hasSize(1);
        assertThat(checkInRepository.findByFlightId(7L)).hasSize(1);
    }

    @Test
    void noShowSweepQueryFindsOnlySweepableRowsPastTheCutoff() {
        CheckIn overdueOpen = checkInRepository.saveAndFlush(
                checkIn().status(CheckInStatus.OPEN).departureTime(DEPARTURE.minusDays(1)).build());
        checkInRepository.saveAndFlush(
                checkIn().status(CheckInStatus.OPEN).departureTime(DEPARTURE.plusDays(5)).build()); // not yet due
        checkInRepository.saveAndFlush(
                checkIn().status(CheckInStatus.CANCELLED).departureTime(DEPARTURE.minusDays(1)).build()); // terminal

        List<CheckIn> overdue = checkInRepository.findByStatusInAndDepartureTimeBefore(
                List.of(CheckInStatus.NOT_OPEN, CheckInStatus.OPEN, CheckInStatus.CHECKED_IN), DEPARTURE);

        assertThat(overdue).extracting(CheckIn::getId).containsExactly(overdueOpen.getId());
    }

    @Test
    void manifestSweepQueryReturnsDistinctFlightIds() {
        checkInRepository.saveAndFlush(checkIn().flightId(7L).departureTime(DEPARTURE.minusDays(1)).build());
        checkInRepository.saveAndFlush(checkIn().flightId(7L).departureTime(DEPARTURE.minusDays(1)).build());
        checkInRepository.saveAndFlush(checkIn().flightId(9L).departureTime(DEPARTURE.plusDays(5)).build());

        List<Long> dueFlightIds = checkInRepository.findDistinctFlightIdByDepartureTimeBefore(DEPARTURE);

        assertThat(dueFlightIds).containsExactly(7L); // flight 9 not yet due, flight 7 appears once despite 2 rows
    }
}
