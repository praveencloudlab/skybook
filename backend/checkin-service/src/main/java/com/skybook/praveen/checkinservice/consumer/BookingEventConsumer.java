package com.skybook.praveen.checkinservice.consumer;

import com.skybook.praveen.checkinservice.dto.request.CreateCheckInRequest;
import com.skybook.praveen.checkinservice.facade.CheckInFacade;
import com.skybook.praveen.checkinservice.service.CheckInService;
import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.common.event.BookingEventPassenger;
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
            default -> log.info("Ignoring {} event for {} (not check-in-relevant in v1)",
                    event.getType(), event.getBookingReference());
        }
    }

    private void handleConfirmed(BookingEvent event) {

        if (event.getBookingId() == null || event.getPassengers() == null) {
            log.warn("Booking CONFIRMED event for {} has no bookingId/passengers (pre-enrichment producer) - skipping",
                    event.getBookingReference());
            return;
        }

        LocalDateTime departureTime = parseEventTime(event.getDepartureTime());

        for (BookingEventPassenger passenger : event.getPassengers()) {

            if (passenger.getBookingPassengerId() == null) {
                log.warn("Passenger {} on booking {} has no bookingPassengerId (pre-enrichment producer) - skipping",
                        passenger.getName(), event.getBookingReference());
                continue;
            }

            CreateCheckInRequest request = new CreateCheckInRequest(
                    event.getBookingId(),
                    event.getBookingReference(),
                    passenger.getBookingPassengerId(),
                    event.getFlightId(),
                    event.getFlightNumber(),
                    event.getOriginAirportCode(),
                    event.getDestinationAirportCode(),
                    departureTime,
                    passenger.getName(),
                    passenger.getSeatNumber(),
                    passenger.getTravelClass(),
                    passenger.getFareType(),
                    // booking-service already validates passport data before
                    // a booking can reach CONFIRMED (BookingValidator.
                    // validatePassportValidForTravel) - true by construction.
                    true
            );

            checkInService.createCheckIn(request, "KAFKA", "BOOKING_EVENT", event.getBookingReference());
        }
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
