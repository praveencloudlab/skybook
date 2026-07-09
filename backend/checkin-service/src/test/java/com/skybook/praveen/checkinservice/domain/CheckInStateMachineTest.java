package com.skybook.praveen.checkinservice.domain;

import com.skybook.praveen.checkinservice.entity.CheckIn;
import com.skybook.praveen.checkinservice.enums.CheckInHistoryType;
import com.skybook.praveen.checkinservice.enums.CheckInStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckInStateMachineTest {

    private final CheckInStateMachine stateMachine = new CheckInStateMachine();

    private CheckIn checkInWith(CheckInStatus status) {
        return CheckIn.builder()
                .id(1L)
                .bookingId(42L)
                .bookingReference("SBTEST")
                .bookingPassengerId(100L)
                .flightId(7L)
                .passengerName("Test Passenger")
                .status(status)
                .build();
    }

    // The full golden transition table from design doc section 4.1 (resolved
    // semantics: NO_SHOW/CANCELLED reachable from every pre-boarding/
    // non-terminal state, no separate CLOSED).
    private final Map<CheckInStatus, Set<CheckInStatus>> validTransitions = Map.of(
            CheckInStatus.NOT_OPEN, Set.of(CheckInStatus.OPEN, CheckInStatus.NO_SHOW, CheckInStatus.CANCELLED),
            CheckInStatus.OPEN, Set.of(CheckInStatus.CHECKED_IN, CheckInStatus.NO_SHOW, CheckInStatus.CANCELLED),
            CheckInStatus.CHECKED_IN, Set.of(CheckInStatus.BOARDED, CheckInStatus.NO_SHOW, CheckInStatus.CANCELLED),
            CheckInStatus.BOARDED, Set.of(CheckInStatus.COMPLETED),
            CheckInStatus.NO_SHOW, Set.of(),
            CheckInStatus.CANCELLED, Set.of(),
            CheckInStatus.COMPLETED, Set.of()
    );

    @Test
    void matchesTheFullGoldenTransitionTable() {
        for (CheckInStatus from : CheckInStatus.values()) {
            for (CheckInStatus to : CheckInStatus.values()) {
                boolean expected = validTransitions.get(from).contains(to);
                assertThat(stateMachine.canTransition(from, to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void terminalStatesHaveNoExits() {
        for (CheckInStatus terminal : Set.of(CheckInStatus.NO_SHOW, CheckInStatus.CANCELLED, CheckInStatus.COMPLETED)) {
            for (CheckInStatus to : CheckInStatus.values()) {
                assertThat(stateMachine.canTransition(terminal, to))
                        .as("%s -> %s must be illegal", terminal, to)
                        .isFalse();
            }
        }
    }

    @Test
    void cancelledIsReachableFromNotOpen() {
        // Design doc section 4.1 - a booking can be cancelled before its
        // check-in window ever opens; this must not be a dead end.
        CheckIn checkIn = checkInWith(CheckInStatus.NOT_OPEN);

        stateMachine.transition(checkIn, CheckInStatus.CANCELLED, CheckInHistoryType.CANCELLED,
                "KAFKA", "BOOKING_EVENT", "SBTEST", "booking cancelled");

        assertThat(checkIn.getStatus()).isEqualTo(CheckInStatus.CANCELLED);
    }

    @Test
    void noShowIsReachableFromNotOpenAndOpen() {
        // Resolved semantics (section 4.1) - NO_SHOW means "didn't fly,"
        // whether or not the passenger ever checked in.
        CheckIn neverOpened = checkInWith(CheckInStatus.NOT_OPEN);
        CheckIn openedButNeverCheckedIn = checkInWith(CheckInStatus.OPEN);

        stateMachine.transition(neverOpened, CheckInStatus.NO_SHOW, CheckInHistoryType.NO_SHOW,
                "SYSTEM", "NO_SHOW_JOB", null, "gate closed");
        stateMachine.transition(openedButNeverCheckedIn, CheckInStatus.NO_SHOW, CheckInHistoryType.NO_SHOW,
                "SYSTEM", "NO_SHOW_JOB", null, "gate closed");

        assertThat(neverOpened.getStatus()).isEqualTo(CheckInStatus.NO_SHOW);
        assertThat(openedButNeverCheckedIn.getStatus()).isEqualTo(CheckInStatus.NO_SHOW);
    }

    @Test
    void transitionRecordsHistoryWithFullProvenance() {
        CheckIn checkIn = checkInWith(CheckInStatus.OPEN);

        stateMachine.transition(checkIn, CheckInStatus.CHECKED_IN, CheckInHistoryType.CHECKED_IN,
                "USER", "API", "SBTEST", "passenger checked in");

        assertThat(checkIn.getStatus()).isEqualTo(CheckInStatus.CHECKED_IN);
        assertThat(checkIn.getHistory()).hasSize(1);
        var entry = checkIn.getHistory().getFirst();
        assertThat(entry.getHistoryType()).isEqualTo(CheckInHistoryType.CHECKED_IN);
        assertThat(entry.getActor()).isEqualTo("USER");
        assertThat(entry.getSource()).isEqualTo("API");
        assertThat(entry.getCorrelationId()).isEqualTo("SBTEST");
        assertThat(entry.getChangedAt()).isNotNull();
        assertThat(entry.getCheckIn()).isSameAs(checkIn);
    }

    @Test
    void invalidTransitionThrowsAndRecordsNothing() {
        CheckIn checkIn = checkInWith(CheckInStatus.CANCELLED);

        assertThatThrownBy(() -> stateMachine.transition(checkIn, CheckInStatus.CHECKED_IN,
                CheckInHistoryType.CHECKED_IN, "USER", "API", null, "nope"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CANCELLED")
                .hasMessageContaining("CHECKED_IN");

        assertThat(checkIn.getStatus()).isEqualTo(CheckInStatus.CANCELLED);
        assertThat(checkIn.getHistory()).isEmpty();
    }

    @Test
    void recordHistoryIsUsableForNonTransitionEvents() {
        CheckIn checkIn = checkInWith(CheckInStatus.NOT_OPEN);

        stateMachine.recordHistory(checkIn, CheckInHistoryType.CHECKIN_CREATED,
                "USER", "API", "SBTEST", "created");

        assertThat(checkIn.getStatus()).isEqualTo(CheckInStatus.NOT_OPEN); // no transition
        assertThat(checkIn.getHistory()).hasSize(1);
    }
}
