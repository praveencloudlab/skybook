package com.skybook.praveen.bookingservice.consumer;

import com.skybook.praveen.common.event.PaymentEvent;
import com.skybook.praveen.bookingservice.facade.BookingFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Sprint 6: closes the loop. payment-service captured money ->
 * PAYMENT_SUCCEEDED -> this consumer confirms the booking (real payment
 * reference recorded), which converts seat holds to reservations and
 * publishes the CONFIRMED event that notification-service emails.
 *
 * Failures are logged, not rethrown - a booking-side bug must not poison
 * the payment topic with endless redeliveries; the payment ledger remains
 * the source of truth for reconciliation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final BookingFacade bookingFacade;

    @KafkaListener(
            topics = "${skybook.kafka.topics.payment-events}",
            containerFactory = "paymentEventContainerFactory")
    public void consume(PaymentEvent event) {

        log.info("Received Payment Event: {} for booking {} (payment {})",
                event.getType(), event.getBookingReference(), event.getPaymentReference());

        if (event.getBookingId() == null) {
            log.warn("Payment event {} carries no bookingId - skipping", event.getType());
            return;
        }

        try {
            switch (event.getType()) {
                case PAYMENT_SUCCEEDED -> bookingFacade.confirmBookingFromPayment(
                        event.getBookingId(), event.getPaymentReference());
                case PAYMENT_FAILED -> log.warn(
                        "Payment {} failed for booking {} ({}) - booking stays CREATED, holds expire via TTL",
                        event.getPaymentReference(), event.getBookingReference(), event.getFailureReason());
                default -> log.info("Ignoring {} for booking {} (handled elsewhere or informational)",
                        event.getType(), event.getBookingReference());
            }
        } catch (RuntimeException e) {
            log.error("Failed to process {} for booking {} - manual reconciliation may be needed",
                    event.getType(), event.getBookingReference(), e);
        }
    }
}
