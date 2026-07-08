package com.skybook.praveen.checkinservice.domain;

import com.skybook.praveen.checkinservice.entity.CheckIn;
import com.skybook.praveen.checkinservice.entity.CheckInHistory;
import com.skybook.praveen.checkinservice.enums.CheckInHistoryType;
import com.skybook.praveen.checkinservice.enums.CheckInStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Validates and applies CheckInStatus transitions (design doc section 4.1)
 * and records every transition onto CheckIn.history in-memory - persisted
 * via cascade when the caller saves. Same design as PaymentStateMachine.
 *
 * NO_SHOW is reachable from every pre-boarding state, not only CHECKED_IN -
 * it means "didn't fly," whether or not the passenger ever checked in
 * (resolved semantics, section 4.1; no separate CLOSED state). CANCELLED is
 * likewise reachable from any non-terminal state, since a booking can be
 * cancelled before its check-in window ever opens.
 */
@Component
public class CheckInStateMachine {

    private static final Map<CheckInStatus, Set<CheckInStatus>> TRANSITIONS = new EnumMap<>(CheckInStatus.class);

    static {
        TRANSITIONS.put(CheckInStatus.NOT_OPEN,
                EnumSet.of(CheckInStatus.OPEN, CheckInStatus.NO_SHOW, CheckInStatus.CANCELLED));
        TRANSITIONS.put(CheckInStatus.OPEN,
                EnumSet.of(CheckInStatus.CHECKED_IN, CheckInStatus.NO_SHOW, CheckInStatus.CANCELLED));
        TRANSITIONS.put(CheckInStatus.CHECKED_IN,
                EnumSet.of(CheckInStatus.BOARDED, CheckInStatus.NO_SHOW, CheckInStatus.CANCELLED));
        TRANSITIONS.put(CheckInStatus.BOARDED, EnumSet.of(CheckInStatus.COMPLETED));
        TRANSITIONS.put(CheckInStatus.NO_SHOW, EnumSet.noneOf(CheckInStatus.class));
        TRANSITIONS.put(CheckInStatus.CANCELLED, EnumSet.noneOf(CheckInStatus.class));
        TRANSITIONS.put(CheckInStatus.COMPLETED, EnumSet.noneOf(CheckInStatus.class));
    }

    public boolean canTransition(CheckInStatus from, CheckInStatus to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public void transition(CheckIn checkIn, CheckInStatus to, CheckInHistoryType historyType,
                            String actor, String source, String correlationId, String details) {

        CheckInStatus from = checkIn.getStatus();

        if (!canTransition(from, to)) {
            throw new IllegalStateException("Cannot transition check-in " + checkIn.getId()
                    + " from " + from + " to " + to);
        }

        checkIn.setStatus(to);
        recordHistory(checkIn, historyType, actor, source, correlationId, details);
    }

    /** Also used directly by services for non-transition events (e.g. CHECKIN_OPENED before a real state change). */
    public void recordHistory(CheckIn checkIn, CheckInHistoryType type,
                               String actor, String source, String correlationId, String details) {

        CheckInHistory entry = CheckInHistory.builder()
                .checkIn(checkIn)
                .historyType(type)
                .actor(actor)
                .source(source)
                .correlationId(correlationId)
                .details(details)
                .changedAt(LocalDateTime.now())
                .build();

        checkIn.getHistory().add(entry);
    }
}
