package com.skybook.praveen.checkinservice.facade;

import com.skybook.praveen.checkinservice.client.FlightCheckInDetails;
import com.skybook.praveen.checkinservice.client.FlightCheckInStatus;
import com.skybook.praveen.checkinservice.client.FlightServiceClient;
import com.skybook.praveen.checkinservice.client.InventoryServiceClient;
import com.skybook.praveen.checkinservice.client.SeatReservationDetails;
import com.skybook.praveen.checkinservice.dto.response.BoardingPassResponse;
import com.skybook.praveen.checkinservice.dto.response.CheckInResponse;
import com.skybook.praveen.checkinservice.exception.SeatUnavailableException;
import com.skybook.praveen.checkinservice.producer.CheckInEventProducer;
import com.skybook.praveen.checkinservice.service.BoardingPassService;
import com.skybook.praveen.checkinservice.service.CheckInService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Orchestration layer (design doc section 2) - the only place that knows
 * flight-service/inventory-service exist and that a check-in state change
 * produces a CheckInEvent. Plain reads and simple field updates (getById,
 * openWindow, assignGate, manual createCheckIn) go straight to
 * CheckInService from the controllers, same split as booking-service's
 * BookingController/BookingFacade.
 *
 * External calls happen outside of and before any DB transaction, same
 * gateway-outside-transaction discipline as PaymentFacade/BookingFacade.
 *
 * No @RequiredArgsConstructor - gateClosesMinutesBeforeDeparture is a
 * @Value primitive, which needs an explicit constructor to be resolvable in
 * a plain unit test, same reasoning as BoardingPassServiceImpl/
 * ManifestServiceImpl.
 */
@Slf4j
@Component
public class CheckInFacade {

    private final CheckInService checkInService;
    private final BoardingPassService boardingPassService;
    private final FlightServiceClient flightServiceClient;
    private final InventoryServiceClient inventoryServiceClient;
    private final CheckInEventProducer producer;
    private final long gateClosesMinutesBeforeDeparture;

    public CheckInFacade(CheckInService checkInService, BoardingPassService boardingPassService,
            FlightServiceClient flightServiceClient, InventoryServiceClient inventoryServiceClient,
            CheckInEventProducer producer,
            @Value("${checkin.boarding.gate-closes-minutes-before-departure:20}") long gateClosesMinutesBeforeDeparture) {
        this.checkInService = checkInService;
        this.boardingPassService = boardingPassService;
        this.flightServiceClient = flightServiceClient;
        this.inventoryServiceClient = inventoryServiceClient;
        this.producer = producer;
        this.gateClosesMinutesBeforeDeparture = gateClosesMinutesBeforeDeparture;
    }

    /**
     * design doc section 5.1/5.2: verifies the flight isn't cancelled and
     * the seat is reserved (both external, both outside any transaction)
     * before the window/document guards and the actual transition run
     * inside CheckInService.
     */
    public CheckInResponse checkIn(Long id) {

        CheckInResponse current = checkInService.getById(id);

        validateFlightNotCancelled(current.flightId());
        validateSeatReserved(current);

        CheckInResponse updated = checkInService.recordCheckIn(id);
        producer.publishPassengerCheckedIn(updated);

        BoardingPassResponse pass = boardingPassService.generate(id);
        producer.publishBoardingPassGenerated(updated, pass.boardingPassNumber());

        return updated;
    }

    /** design doc section 5.4: flight-cancelled check is external, the boarding-window guard is CheckInService's. */
    public CheckInResponse board(Long id) {

        CheckInResponse current = checkInService.getById(id);
        validateFlightNotCancelled(current.flightId());

        CheckInResponse updated = checkInService.recordBoarding(id);
        producer.publishPassengerBoarded(updated);

        return updated;
    }

    /**
     * design doc section 5.6: reserve the new seat first (external, outside
     * any transaction) - if it's unavailable nothing has changed yet, same
     * compensate-on-failure ordering as BookingFacade.holdSeatsOrCompensate.
     * Only once the new seat is secured does the DB change, and only after
     * that does the old reservation get released (quietly - a cleanup
     * failure here must not undo an otherwise-successful seat change).
     */
    public CheckInResponse changeSeat(Long id, String newSeatNumber) {

        CheckInResponse current = checkInService.getById(id);
        String oldSeatNumber = current.seatNumber();

        inventoryServiceClient.reserveSeat(current.flightId(), newSeatNumber,
                current.bookingId(), current.bookingPassengerId());

        CheckInResponse updated = checkInService.changeSeatNumber(id, newSeatNumber);

        if (oldSeatNumber != null && !oldSeatNumber.isBlank()) {
            inventoryServiceClient.cancelReservationQuietly(current.flightId(), oldSeatNumber,
                    current.bookingId(), "Seat changed to " + newSeatNumber);
        }

        boardingPassService.reissueForSeatChange(id)
                .ifPresent(pass -> producer.publishBoardingPassGenerated(updated, pass.boardingPassNumber()));

        return updated;
    }

    /** BookingEvent CANCELLED (design doc section 8) - cascades cancellation and revokes any active boarding pass. */
    public void cancelForBooking(Long bookingId, String reason) {

        List<CheckInResponse> cancelled = checkInService.cancelAllForBooking(bookingId, reason);

        for (CheckInResponse checkIn : cancelled) {
            boardingPassService.revokeActive(checkIn.id(), reason);
            producer.publishPassengerCheckInCancelled(checkIn);
        }
    }

    /** Scheduler entry point (design doc section 5.7/10) - returns the number of rows swept. */
    public int sweepNoShows() {

        LocalDateTime cutoff = LocalDateTime.now().plusMinutes(gateClosesMinutesBeforeDeparture);
        List<CheckInResponse> swept = checkInService.sweepNoShows(cutoff);

        for (CheckInResponse checkIn : swept) {
            boardingPassService.revokeActive(checkIn.id(), "No-show");
            producer.publishPassengerNoShow(checkIn);
        }

        return swept.size();
    }

    private void validateFlightNotCancelled(Long flightId) {
        FlightCheckInDetails flight = flightServiceClient.getFlight(flightId);
        if (flight.status() == FlightCheckInStatus.CANCELLED) {
            throw new IllegalStateException("Cannot proceed - flight " + flightId + " is cancelled");
        }
    }

    /**
     * Empty reservations list = no inventory tracked for this flight, or
     * nothing to check against either way - permissive, same graceful-
     * degradation policy booking-service applies to seat holds (design doc
     * section 5.1/9.1). Only a non-empty list missing this exact seat is a
     * real conflict.
     */
    private void validateSeatReserved(CheckInResponse checkIn) {

        List<SeatReservationDetails> reservations = inventoryServiceClient.getReservationsForBooking(checkIn.bookingId());
        if (reservations.isEmpty()) {
            return;
        }

        boolean reserved = reservations.stream()
                .anyMatch(r -> r.seatNumber().equals(checkIn.seatNumber()) && "RESERVED".equalsIgnoreCase(r.status()));

        if (!reserved) {
            throw new SeatUnavailableException(checkIn.flightId(), checkIn.seatNumber(),
                    "not reserved in inventory - cannot check in");
        }
    }
}
