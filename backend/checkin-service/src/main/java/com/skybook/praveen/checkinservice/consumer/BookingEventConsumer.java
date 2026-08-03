package com.skybook.praveen.checkinservice.consumer;

import com.skybook.praveen.checkinservice.dto.request.CreateCheckInRequest;
import com.skybook.praveen.checkinservice.facade.CheckInFacade;
import com.skybook.praveen.checkinservice.service.CheckInService;
import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.common.event.BookingEventPassenger;
import com.skybook.praveen.common.event.BookingEventSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Reacts to booking-service's events (design doc section 8):
 * CONFIRMED -> create one CheckIn per passenger (idempotent by
 *              bookingPassengerId, so a redelivered event is a no-op)
 * CANCELLED -> cascade-cancel every non-terminal CheckIn for the booking
 * everything else -> logged and ignored (CREATED is pending payment,
 * nothing to check in for yet; COMPLETED/EXPIRED aren't check-in-relevant)
 *
 * Deliberately does not also consume PaymentEvent - by the time
 * BookingEvent{CONFIRMED} exists, payment has already succeeded (design doc
 * section 8).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingEventConsumer {

    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CheckInService checkInService;
    private final CheckInFacade checkInFacade;

    @KafkaListener(
            topics = "${skybook.kafka.topics.booking-events}",
            containerFactory = "bookingEventContainerFactory")
    public void consume(BookingEvent event) {

        log.info("Received Booking Event: {} for {}", event.getType(), event.getBookingReference());

        switch (event.getType()) {
            case CONFIRMED -> handleConfirmed(event);
            case CANCELLED -> checkInFacade.cancelForBooking(event.getBookingId(),
                    "Booking " + event.getBookingReference() + " cancelled");
            // Passengers/segment cancelled off a SURVIVING booking: close
            // exactly those rows' check-ins (they were never checked in - the
            // guard blocks that - so there is no pass to revoke). Without this
            // the records stayed OPEN forever and could still check in.
            case PARTIALLY_CANCELLED -> {
                if (event.getCancelledBookingPassengerIds() != null) {
                    event.getCancelledBookingPassengerIds().forEach(rowId ->
                            checkInService.cancelForBookingPassenger(rowId,
                                    "Cancelled off booking " + event.getBookingReference()));
                }
            }
            default -> log.info("Ignoring {} event for {} (not check-in-relevant in v1)",
                    event.getType(), event.getBookingReference());
        }
    }

    private void handleConfirmed(BookingEvent event) {

        if (event.getBookingId() == null) {
            log.warn("Booking CONFIRMED event for {} has no bookingId (pre-enrichment producer) - skipping",
                    event.getBookingReference());
            return;
        }

        // Nested segments preferred (ROUND_TRIP_MODULE.md §6): each leg's
        // passengers get a CheckIn snapshotting THAT leg's flight - this is
        // what makes per-direction check-in work on a round trip. Old events
        // (null segments) fall back to the top-level flight + flat list.
        if (event.getSegments() != null && !event.getSegments().isEmpty()) {
            for (BookingEventSegment segment : event.getSegments()) {
                if (segment.getPassengers() == null) {
                    continue;
                }
                LocalDateTime departureTime = parseEventTime(segment.getDepartureTime());
                for (BookingEventPassenger passenger : segment.getPassengers()) {
                    createCheckIn(event, passenger, segment.getFlightId(), segment.getFlightNumber(),
                            segment.getOriginAirportCode(), segment.getDestinationAirportCode(), departureTime,
                            segment.getDepartureTerminal(), segment.getArrivalTerminal());
                }
            }
            return;
        }

        if (event.getPassengers() == null) {
            log.warn("Booking CONFIRMED event for {} has no passengers (pre-enrichment producer) - skipping",
                    event.getBookingReference());
            return;
        }

        LocalDateTime departureTime = parseEventTime(event.getDepartureTime());
        for (BookingEventPassenger passenger : event.getPassengers()) {
            // Old flat events carry no terminals - honest null, never invented.
            createCheckIn(event, passenger, event.getFlightId(), event.getFlightNumber(),
                    event.getOriginAirportCode(), event.getDestinationAirportCode(), departureTime,
                    null, null);
        }
    }

    private void createCheckIn(BookingEvent event, BookingEventPassenger passenger,
                               Long flightId, String flightNumber,
                               String originAirportCode, String destinationAirportCode,
                               LocalDateTime departureTime,
                               String departureTerminal, String arrivalTerminal) {

        if (passenger.getBookingPassengerId() == null) {
            log.warn("Passenger {} on booking {} has no bookingPassengerId (pre-enrichment producer) - skipping",
                    passenger.getName(), event.getBookingReference());
            return;
        }

        // A CLOSED row on a refreshed CONFIRMED event is one that was
        // exchanged away (Premium date change, ROUND_TRIP_MODULE.md §11) -
        // its old CheckIn must cancel, never be (re)created.
        if ("CLOSED".equals(passenger.getCheckInStatus())) {
            checkInService.cancelForBookingPassenger(passenger.getBookingPassengerId(),
                    "Row exchanged off booking " + event.getBookingReference());
            return;
        }

        CreateCheckInRequest request = new CreateCheckInRequest(
                event.getBookingId(),
                event.getBookingReference(),
                passenger.getBookingPassengerId(),
                flightId,
                flightNumber,
                originAirportCode,
                destinationAirportCode,
                departureTime,
                departureTerminal,
                arrivalTerminal,
                passenger.getName(),
                event.getContactEmail(),
                passenger.getSeatNumber(),
                passenger.getTravelClass(),
                passenger.getFareType(),
                // §9: the surcharge actually PAID at booking becomes the
                // free-seat-change ceiling; legacy events carry null => 0.
                passenger.getSeatSurcharge(),
                passenger.getCurrency(),
                // §4.2: ownership snapshot from the CONFIRMED event.
                event.getOwnerSubject(),
                // booking-service already validates passport data before
                // a booking can reach CONFIRMED (BookingValidator.
                // validatePassportValidForTravel) - true by construction.
                true
        );

        checkInService.createCheckIn(request, "KAFKA", "BOOKING_EVENT", event.getBookingReference());
    }

    private static LocalDateTime parseEventTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, EVENT_TIME);
        } catch (DateTimeParseException malformed) {
            return null;
        }
    }
}
