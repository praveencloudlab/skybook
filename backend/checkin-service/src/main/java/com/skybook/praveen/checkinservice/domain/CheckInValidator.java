package com.skybook.praveen.checkinservice.domain;

import com.skybook.praveen.checkinservice.entity.CheckIn;
import com.skybook.praveen.checkinservice.enums.CheckInStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Guard clauses for command flows (design doc section 5) - throws with
 * precise reasons. State-transition legality itself is
 * CheckInStateMachine's job (including "cancelled bookings cannot check in"
 * - CANCELLED has no outgoing transitions, so no separate check is needed
 * here); this class checks everything else: window timing, document
 * presence, and which statuses a non-transition action (seat change,
 * baggage) is allowed from.
 *
 * Pure - no I/O. Seat-reserved and flight-not-cancelled checks are external
 * calls and belong to the facade (design doc section 9.1/9.2), not here.
 */
@Component
public class CheckInValidator {

    private static final Set<CheckInStatus> SEAT_CHANGE_ALLOWED = EnumSet.of(
            CheckInStatus.OPEN, CheckInStatus.CHECKED_IN);

    private final long opensHoursBeforeDeparture;
    private final long closesMinutesBeforeDeparture;
    private final long boardingOpensMinutesBeforeDeparture;
    private final long gateClosesMinutesBeforeDeparture;

    public CheckInValidator(
            @Value("${checkin.window.opens-hours-before-departure:24}") long opensHoursBeforeDeparture,
            @Value("${checkin.window.closes-minutes-before-departure:45}") long closesMinutesBeforeDeparture,
            @Value("${checkin.boarding.opens-minutes-before-departure:45}") long boardingOpensMinutesBeforeDeparture,
            @Value("${checkin.boarding.gate-closes-minutes-before-departure:20}") long gateClosesMinutesBeforeDeparture) {
        this.opensHoursBeforeDeparture = opensHoursBeforeDeparture;
        this.closesMinutesBeforeDeparture = closesMinutesBeforeDeparture;
        this.boardingOpensMinutesBeforeDeparture = boardingOpensMinutesBeforeDeparture;
        this.gateClosesMinutesBeforeDeparture = gateClosesMinutesBeforeDeparture;
    }

    /**
     * The status to DISPLAY for a check-in, reconciled against the clock.
     *
     * <p>Check-in window opening is event-driven ({@code openWindow}) with no
     * scheduler yet, so a row created before its window opens stays stored as
     * {@code NOT_OPEN} even after the clock passes the open time - which made the
     * UI say "check-in opens around &lt;a past date&gt;". This derives what the
     * window actually is now (using the same configured offsets as
     * {@link #validateCheckInWindowOpen}), for read/display only - it never
     * mutates the row. Terminal states (checked in, boarded, cancelled) are left
     * exactly as stored.
     */
    public CheckInStatus effectiveStatus(CheckIn checkIn, LocalDateTime now) {
        CheckInStatus stored = checkIn.getStatus();
        LocalDateTime departureTime = checkIn.getDepartureTime();
        if (departureTime == null
                || stored == CheckInStatus.CHECKED_IN
                || stored == CheckInStatus.BOARDED
                || stored == CheckInStatus.CANCELLED
                || stored == CheckInStatus.NO_SHOW) {
            return stored;
        }
        LocalDateTime opensAt = departureTime.minusHours(opensHoursBeforeDeparture);
        LocalDateTime closesAt = departureTime.minusMinutes(closesMinutesBeforeDeparture);
        if (!now.isBefore(closesAt)) {
            // Window has closed and the passenger never checked in.
            return CheckInStatus.NO_SHOW;
        }
        if (!now.isBefore(opensAt)) {
            return CheckInStatus.OPEN;
        }
        return stored; // still genuinely before the window
    }

    /** Design doc section 5.1/5.2: window opens N hours before departure, closes M minutes before. */
    public void validateCheckInWindowOpen(CheckIn checkIn, LocalDateTime now) {

        LocalDateTime departureTime = requireDepartureTime(checkIn);
        LocalDateTime opensAt = departureTime.minusHours(opensHoursBeforeDeparture);
        LocalDateTime closesAt = departureTime.minusMinutes(closesMinutesBeforeDeparture);

        if (now.isBefore(opensAt)) {
            throw new IllegalStateException("Check-in for flight " + checkIn.getFlightId()
                    + " does not open until " + opensAt);
        }
        if (!now.isBefore(closesAt)) {
            throw new IllegalStateException("Check-in for flight " + checkIn.getFlightId()
                    + " closed at " + closesAt);
        }
    }

    /** Design doc section 5.2: cannot check in if passport/document data is missing. */
    public void validateDocumentVerified(CheckIn checkIn) {
        if (!checkIn.isDocumentVerified()) {
            throw new IllegalStateException("Check-in " + checkIn.getId()
                    + " is missing required passport/document data");
        }
    }

    /** Design doc section 5.4: cannot board before boarding opens or after the gate closes. */
    public void validateBoardingWindow(CheckIn checkIn, LocalDateTime now) {

        LocalDateTime departureTime = requireDepartureTime(checkIn);
        LocalDateTime boardingOpensAt = departureTime.minusMinutes(boardingOpensMinutesBeforeDeparture);
        LocalDateTime gateClosesAt = departureTime.minusMinutes(gateClosesMinutesBeforeDeparture);

        if (now.isBefore(boardingOpensAt)) {
            throw new IllegalStateException("Boarding for flight " + checkIn.getFlightId()
                    + " does not open until " + boardingOpensAt);
        }
        if (!now.isBefore(gateClosesAt)) {
            throw new IllegalStateException("Gate for flight " + checkIn.getFlightId()
                    + " closed at " + gateClosesAt);
        }
    }

    /** Design doc section 5.6: seat change only before boarding. */
    public void validateSeatChangeAllowed(CheckIn checkIn) {
        if (!SEAT_CHANGE_ALLOWED.contains(checkIn.getStatus())) {
            throw new IllegalStateException("Check-in " + checkIn.getId()
                    + " is " + checkIn.getStatus() + " - cannot change seat");
        }
    }

    /** Design doc section 5.5: baggage only for CHECKED_IN passengers. */
    public void validateBaggageAllowed(CheckIn checkIn) {
        if (checkIn.getStatus() != CheckInStatus.CHECKED_IN) {
            throw new IllegalStateException("Check-in " + checkIn.getId()
                    + " is " + checkIn.getStatus() + " - cannot add baggage");
        }
    }

    /** Design doc section 5.7: a manifest can only be finalized after gate close or departure. */
    public void validateManifestFinalizable(Long flightId, LocalDateTime departureTime, LocalDateTime now) {

        LocalDateTime gateClosesAt = departureTime.minusMinutes(gateClosesMinutesBeforeDeparture);

        if (now.isBefore(gateClosesAt)) {
            throw new IllegalStateException("Manifest for flight " + flightId
                    + " cannot be finalized before gate close at " + gateClosesAt);
        }
    }

    private LocalDateTime requireDepartureTime(CheckIn checkIn) {
        if (checkIn.getDepartureTime() == null) {
            throw new IllegalStateException("Check-in " + checkIn.getId() + " has no departure time on record");
        }
        return checkIn.getDepartureTime();
    }
}
