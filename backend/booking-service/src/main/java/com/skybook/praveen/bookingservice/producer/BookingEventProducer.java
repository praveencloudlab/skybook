package com.skybook.praveen.bookingservice.producer;

import com.skybook.praveen.bookingservice.dto.response.BookingResponse;
import com.skybook.praveen.common.constants.KafkaTopics;
import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.common.event.BookingEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes BookingEvent to Kafka for notification-service to pick up
 * (docs section 8) - no synchronous REST call for notifications.
 *
 * Called by BookingFacade AFTER BookingService's @Transactional method has
 * already returned (and therefore already committed) - see BookingFacade
 * for why that's deliberate instead of using @TransactionalEventListener.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingEventProducer {

    private final KafkaTemplate<String, BookingEvent> kafkaTemplate;

    public void publishBookingCreated(BookingResponse booking) {
        publish(booking, BookingEventType.CREATED,
                "Your SkyBook booking " + booking.bookingReference() + " has been created",
                "Thanks for booking with SkyBook! Your booking reference is " + booking.bookingReference()
                        + ". Complete payment to confirm your seat(s).");
    }

    public void publishBookingConfirmed(BookingResponse booking) {
        publish(booking, BookingEventType.CONFIRMED,
                "Your SkyBook booking " + booking.bookingReference() + " is confirmed",
                "Good news - your booking " + booking.bookingReference() + " is confirmed. Have a great flight.");
    }

    public void publishBookingCancelled(BookingResponse booking) {
        publish(booking, BookingEventType.CANCELLED,
                "Your SkyBook booking " + booking.bookingReference() + " has been cancelled",
                "Your booking " + booking.bookingReference()
                        + " has been cancelled. If a refund is due, it will be processed shortly.");
    }

    private void publish(BookingResponse booking, BookingEventType type, String subject, String message) {

        if (booking.contact() == null) {
            log.warn("Booking {} has no contact on file - skipping notification", booking.bookingReference());
            return;
        }

        BookingEvent event = BookingEvent.builder()
                .bookingReference(booking.bookingReference())
                .type(type)
                .contactEmail(booking.contact().contactEmail())
                .contactName(booking.contact().contactName())
                .subject(subject)
                .message(message)
                .build();

        kafkaTemplate.send(KafkaTopics.BOOKING_EVENTS, event);

        log.info("Published {} event for booking {}", type, booking.bookingReference());
    }
}
