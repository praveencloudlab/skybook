package com.skybook.praveen.paymentservice.consumer;

import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.paymentservice.dto.request.RefundRequest;
import com.skybook.praveen.paymentservice.dto.response.PaymentResponse;
import com.skybook.praveen.paymentservice.enums.PaymentStatus;
import com.skybook.praveen.paymentservice.exception.PaymentNotFoundException;
import com.skybook.praveen.paymentservice.facade.PaymentFacade;
import com.skybook.praveen.paymentservice.service.ActionContext;
import com.skybook.praveen.paymentservice.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to booking-service's events (design doc section 10):
 * CREATED  -> auto-create a PENDING payment (idempotent by bookingId)
 * CANCELLED-> refund if captured, cancel/void otherwise, no-op if terminal
 * everything else -> logged and ignored (CONFIRMED is booking's reaction to
 * payment, not the reverse - Sprint 6).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingEventConsumer {

    private final PaymentService paymentService;
    private final PaymentFacade paymentFacade;

    @KafkaListener(
            topics = "${skybook.kafka.topics.booking-events}",
            containerFactory = "bookingEventContainerFactory")
    public void consume(BookingEvent event) {

        log.info("Received Booking Event: {} for {}", event.getType(), event.getBookingReference());

        if (event.getBookingId() == null) {
            log.warn("Booking event {} for {} has no bookingId (pre-enrichment producer) - skipping",
                    event.getType(), event.getBookingReference());
            return;
        }

        ActionContext ctx = ActionContext.kafka(event.getBookingReference());

        switch (event.getType()) {
            case CREATED -> paymentService.createFromBookingEvent(event);
            case CANCELLED -> handleBookingCancelled(event, ctx);
            default -> log.info("Ignoring {} event for {} (not payment-relevant in v1)",
                    event.getType(), event.getBookingReference());
        }
    }

    private void handleBookingCancelled(BookingEvent event, ActionContext ctx) {

        PaymentResponse payment;
        try {
            payment = paymentService.getByBookingId(event.getBookingId());
        } catch (PaymentNotFoundException e) {
            log.info("Booking {} cancelled but no payment exists - nothing to do", event.getBookingReference());
            return;
        }

        PaymentStatus status = payment.status();

        if (status == PaymentStatus.CAPTURED || status == PaymentStatus.PARTIALLY_REFUNDED) {
            paymentFacade.refund(payment.id(),
                    new RefundRequest(null, "Booking " + event.getBookingReference() + " cancelled"), ctx);
        } else if (status == PaymentStatus.PENDING || status == PaymentStatus.AUTHORIZED
                || status == PaymentStatus.AUTHORIZATION_FAILED || status == PaymentStatus.CAPTURE_FAILED) {
            paymentFacade.cancel(payment.id(), ctx);
        } else {
            log.info("Booking {} cancelled; payment {} already {} - no action",
                    event.getBookingReference(), payment.paymentReference(), status);
        }
    }
}
