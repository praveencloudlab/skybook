package com.skybook.praveen.bookingservice.producer;

import com.skybook.praveen.bookingservice.client.FlightDetails;
import com.skybook.praveen.bookingservice.dto.response.BookingResponse;
import com.skybook.praveen.common.constants.KafkaTopics;
import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.common.event.BookingEventPassenger;
import com.skybook.praveen.common.event.BookingEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * Publishes BookingEvent to Kafka for notification-service (emails) and
 * payment-service (payment lifecycle) - no synchronous REST calls.
 *
 * Called by BookingFacade AFTER BookingService's @Transactional method has
 * already returned (and therefore already committed) - see BookingFacade
 * for why that's deliberate instead of using @TransactionalEventListener.
 *
 * flight is nullable: the facade passes it when it has (or could fetch) the
 * flight details; the email template degrades gracefully without them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingEventProducer {

    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final KafkaTemplate<String, BookingEvent> kafkaTemplate;

    public void publishBookingCreated(BookingResponse booking, FlightDetails flight) {
        publish(booking, flight, BookingEventType.CREATED,
                "Your SkyBook booking " + booking.bookingReference() + " has been created",
                "Thanks for booking with SkyBook! Your booking reference is " + booking.bookingReference()
                        + ". Complete payment to confirm your seat(s).");
    }

    public void publishBookingConfirmed(BookingResponse booking, FlightDetails flight) {
        publish(booking, flight, BookingEventType.CONFIRMED,
                "Your SkyBook booking " + booking.bookingReference() + " is confirmed",
                "Good news - your booking " + booking.bookingReference() + " is confirmed. Have a great flight.");
    }

    public void publishBookingCancelled(BookingResponse booking, FlightDetails flight) {
        publish(booking, flight, BookingEventType.CANCELLED,
                "Your SkyBook booking " + booking.bookingReference() + " has been cancelled",
                "Your booking " + booking.bookingReference()
                        + " has been cancelled. If a refund is due, it will be processed shortly.");
    }

    private void publish(BookingResponse booking, FlightDetails flight,
                         BookingEventType type, String subject, String message) {

        if (booking.contact() == null) {
            log.warn("Booking {} has no contact on file - skipping notification", booking.bookingReference());
            return;
        }

        BookingEvent.BookingEventBuilder event = BookingEvent.builder()
                .bookingReference(booking.bookingReference())
                .type(type)
                .contactEmail(booking.contact().contactEmail())
                .contactName(booking.contact().contactName())
                .subject(subject)
                .message(message)
                // Structured details for notification-service's HTML template
                // and payment-service's consumer.
                .bookingId(booking.id())
                .bookingStatus(booking.bookingStatus() != null ? booking.bookingStatus().name() : null)
                .flightId(booking.flightId())
                .bookingDate(booking.bookingDate() != null
                        ? booking.bookingDate().format(EVENT_TIME) : null)
                .passengers(booking.passengers() == null ? null : booking.passengers().stream()
                        .map(p -> BookingEventPassenger.builder()
                                .bookingPassengerId(p.id())
                                .name((p.firstName() + " " + p.lastName()).trim())
                                .seatNumber(p.seatNumber())
                                .travelClass(p.travelClass() != null ? p.travelClass().name() : null)
                                .fareType(p.fareType() != null ? p.fareType().name() : null)
                                .fare(p.fare())
                                // §9: check-in snapshots the surcharge actually
                                // PAID as its free-change entitlement ceiling.
                                .seatSurcharge(p.seatSurcharge())
                                .currency(p.currency())
                                .checkInStatus(p.checkInStatus() != null ? p.checkInStatus().name() : null)
                                .build())
                        .toList())
                .totalFare(booking.totalFare())
                .currency(booking.payment() != null ? booking.payment().currency() : null)
                .paymentStatus(booking.payment() != null && booking.payment().paymentStatus() != null
                        ? booking.payment().paymentStatus().name() : null);

        // Flight context - best-effort (null when flight-service was
        // unreachable or the caller had no reason to fetch it).
        if (flight != null) {
            event.flightNumber(flight.flightNumber())
                    .originAirportCode(flight.originAirportCode())
                    .destinationAirportCode(flight.destinationAirportCode())
                    .departureTime(flight.departureTime() != null
                            ? flight.departureTime().format(EVENT_TIME) : null)
                    .arrivalTime(flight.arrivalTime() != null
                            ? flight.arrivalTime().format(EVENT_TIME) : null);
        }

        // Async but no longer fire-and-forget: broker-side failures now log
        // at ERROR into the centralized pipeline (RESILIENCE_MODULE.md §10).
        kafkaTemplate.send(KafkaTopics.BOOKING_EVENTS, event.build())
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish {} event for booking {} to {}",
                                type, booking.bookingReference(), KafkaTopics.BOOKING_EVENTS, ex);
                    }
                });

        log.info("Published {} event for booking {}", type, booking.bookingReference());
    }
}
