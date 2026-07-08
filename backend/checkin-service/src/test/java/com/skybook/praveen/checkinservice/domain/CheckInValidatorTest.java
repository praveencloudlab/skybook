package com.skybook.praveen.checkinservice.domain;

import com.skybook.praveen.checkinservice.entity.CheckIn;
import com.skybook.praveen.checkinservice.enums.CheckInStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckInValidatorTest {

    // opens 24h before, closes 45m before; boarding opens 45m before, gate closes 20m before.
    private final CheckInValidator validator = new CheckInValidator(24, 45, 45, 20);

    private static final LocalDateTime DEPARTURE = LocalDateTime.of(2026, 7, 8, 18, 0);

    private CheckIn checkInWith(CheckInStatus status) {
        return CheckIn.builder()
                .id(1L)
                .bookingId(42L)
                .bookingReference("SBTEST")
                .bookingPassengerId(100L)
                .flightId(7L)
                .passengerName("Test Passenger")
                .departureTime(DEPARTURE)
                .status(status)
                .documentVerified(true)
                .build();
    }

    @Test
    void windowNotYetOpenThrows() {
        CheckIn checkIn = checkInWith(CheckInStatus.NOT_OPEN);
        LocalDateTime now = DEPARTURE.minusHours(25); // before the 24h-before opening

        assertThatThrownBy(() -> validator.validateCheckInWindowOpen(checkIn, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not open until");
    }

    @Test
    void windowClosedThrows() {
        CheckIn checkIn = checkInWith(CheckInStatus.OPEN);
        LocalDateTime now = DEPARTURE.minusMinutes(44); // after the 45m-before close

        assertThatThrownBy(() -> validator.validateCheckInWindowOpen(checkIn, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed at");
    }

    @Test
    void windowOpenWithinBoundsPasses() {
        CheckIn checkIn = checkInWith(CheckInStatus.OPEN);
        LocalDateTime now = DEPARTURE.minusHours(2); // well within [24h, 45m) before

        assertThatCode(() -> validator.validateCheckInWindowOpen(checkIn, now)).doesNotThrowAnyException();
    }

    @Test
    void missingDepartureTimeThrows() {
        CheckIn checkIn = checkInWith(CheckInStatus.OPEN);
        checkIn.setDepartureTime(null);

        assertThatThrownBy(() -> validator.validateCheckInWindowOpen(checkIn, LocalDateTime.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no departure time");
    }

    @Test
    void missingDocumentVerificationThrows() {
        CheckIn checkIn = checkInWith(CheckInStatus.OPEN);
        checkIn.setDocumentVerified(false);

        assertThatThrownBy(() -> validator.validateDocumentVerified(checkIn))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("passport/document");
    }

    @Test
    void documentVerifiedPasses() {
        CheckIn checkIn = checkInWith(CheckInStatus.OPEN);
        assertThatCode(() -> validator.validateDocumentVerified(checkIn)).doesNotThrowAnyException();
    }

    @Test
    void boardingTooEarlyThrows() {
        CheckIn checkIn = checkInWith(CheckInStatus.CHECKED_IN);
        LocalDateTime now = DEPARTURE.minusMinutes(46); // before the 45m-before boarding open

        assertThatThrownBy(() -> validator.validateBoardingWindow(checkIn, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not open until");
    }

    @Test
    void boardingAfterGateCloseThrows() {
        CheckIn checkIn = checkInWith(CheckInStatus.CHECKED_IN);
        LocalDateTime now = DEPARTURE.minusMinutes(19); // after the 20m-before gate close

        assertThatThrownBy(() -> validator.validateBoardingWindow(checkIn, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed at");
    }

    @Test
    void boardingWithinWindowPasses() {
        CheckIn checkIn = checkInWith(CheckInStatus.CHECKED_IN);
        LocalDateTime now = DEPARTURE.minusMinutes(30); // within [45m, 20m) before

        assertThatCode(() -> validator.validateBoardingWindow(checkIn, now)).doesNotThrowAnyException();
    }

    @Test
    void seatChangeAllowedOnlyForOpenOrCheckedIn() {
        Set<CheckInStatus> allowed = EnumSet.of(CheckInStatus.OPEN, CheckInStatus.CHECKED_IN);

        for (CheckInStatus status : CheckInStatus.values()) {
            CheckIn checkIn = checkInWith(status);
            if (allowed.contains(status)) {
                assertThatCode(() -> validator.validateSeatChangeAllowed(checkIn))
                        .as(status.name()).doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> validator.validateSeatChangeAllowed(checkIn))
                        .as(status.name()).isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Test
    void baggageAllowedOnlyForCheckedIn() {
        for (CheckInStatus status : CheckInStatus.values()) {
            CheckIn checkIn = checkInWith(status);
            if (status == CheckInStatus.CHECKED_IN) {
                assertThatCode(() -> validator.validateBaggageAllowed(checkIn))
                        .as(status.name()).doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> validator.validateBaggageAllowed(checkIn))
                        .as(status.name()).isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Test
    void manifestCannotFinalizeBeforeGateClose() {
        LocalDateTime now = DEPARTURE.minusMinutes(21); // before the 20m-before gate close

        assertThatThrownBy(() -> validator.validateManifestFinalizable(7L, DEPARTURE, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be finalized");
    }

    @Test
    void manifestFinalizableAfterGateClose() {
        LocalDateTime now = DEPARTURE.minusMinutes(19); // after the 20m-before gate close

        assertThatCode(() -> validator.validateManifestFinalizable(7L, DEPARTURE, now)).doesNotThrowAnyException();
    }
}
