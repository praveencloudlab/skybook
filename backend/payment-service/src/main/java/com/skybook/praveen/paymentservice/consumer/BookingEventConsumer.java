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
            case PARTIALLY_CANCELLED -> handleBookingPartiallyCancelled(event, ctx);
            default -> log.info("Ignoring {} event for {} (not payment-relevant in v1)",
                    event.getType(), event.getBookingReference());
        }
    }

    /**
     * Passengers/segment cancelled off a SURVIVING booking: refund exactly the
     * cancelled rows' fare lines (the event's refundBreakdown), scaled by the
     * cancellation-policy tier. The payment goes PARTIALLY_REFUNDED and stays
     * live for the remaining passengers.
     */
    private void handleBookingPartiallyCancelled(BookingEvent event, ActionContext ctx) {

        PaymentResponse payment;
        try {
            payment = paymentService.getByBookingId(event.getBookingId());
        } catch (PaymentNotFoundException e) {
            log.info("Booking {} partially cancelled but no payment exists - nothing to do",
                    event.getBookingReference());
            return;
        }

        if (payment.status() != PaymentStatus.CAPTURED && payment.status() != PaymentStatus.PARTIALLY_REFUNDED) {
            log.info("Booking {} partially cancelled; payment {} is {} - no money captured for these rows, no action",
                    event.getBookingReference(), payment.paymentReference(), payment.status());
            return;
        }

        int tierPercent = event.getRefundTierPercent() != null ? event.getRefundTierPercent() : 100;
        // PREMIUM lines ride their own waiver tier (FARE_RULES); null = same tier.
        Integer premiumPercent = event.getPremiumTierPercent();
        if (tierPercent == 0) {
            log.info("Booking {} partially cancelled in the zero-refund window - fare forfeited, keeping payment {}",
                    event.getBookingReference(), payment.paymentReference());
            return;
        }
        if (event.getRefundBreakdown() == null || event.getRefundBreakdown().isBlank()) {
            log.warn("Booking {} PARTIALLY_CANCELLED event carries no refundBreakdown - cannot refund blind, skipping",
                    event.getBookingReference());
            return;
        }

        java.util.List<com.skybook.praveen.paymentservice.dto.request.FareLineRequest> lines =
                com.skybook.praveen.paymentservice.domain.RefundCalculator
                        .parse(event.getRefundBreakdown(), java.math.BigDecimal.ZERO).stream()
                        .map(line -> new com.skybook.praveen.paymentservice.dto.request.FareLineRequest(
                                line.fareType(), line.amount()))
                        .toList();

        // The refund's CAUSE, derived deterministically from the event so a
        // redelivery names the same cause and the V3 unique index refuses the
        // second row (IDEMPOTENCY_MODULE.md §3.6). The old status guard could
        // not do this job: the first partial refund leaves the payment in
        // exactly the PARTIALLY_REFUNDED state the guard accepts, and this
        // consumer rethrows on failure, so DLT retries made redelivery routine.
        String cause = "partial:" + event.getBookingId() + ":" + cancelledRowsKey(event);
        try {
            paymentFacade.refund(payment.id(),
                    new RefundRequest(lines, tierPercent, premiumPercent,
                            "Partial cancellation on booking " + event.getBookingReference(), cause), ctx);
        } catch (org.springframework.dao.DataIntegrityViolationException alreadyRefunded) {
            log.info("Booking {} partial cancellation {} already refunded - redelivery, nothing to do",
                    event.getBookingReference(), cause);
        }
    }

    /**
     * A stable key for WHICH rows a partial cancellation covers: the cancelled
     * passenger-row ids, sorted. Two different partial cancellations on the
     * same booking (say rows 12+14, then row 15) produce different causes and
     * both refund; the SAME cancellation redelivered produces the same cause
     * and only the first insert survives.
     */
    private static String cancelledRowsKey(BookingEvent event) {
        java.util.List<Long> ids = event.getCancelledBookingPassengerIds();
        if (ids == null || ids.isEmpty()) {
            return "all";
        }
        return ids.stream().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
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

        // Time-tier percent quoted at cancellation (null on legacy events = 100).
        int tierPercent = event.getRefundTierPercent() != null ? event.getRefundTierPercent() : 100;
        // PREMIUM lines ride their own waiver tier (FARE_RULES); null = same tier.
        Integer premiumPercent = event.getPremiumTierPercent();

        if (status == PaymentStatus.CAPTURED || status == PaymentStatus.PARTIALLY_REFUNDED) {
            if (tierPercent == 0) {
                // Same-day forfeiture: the passenger was told "zero refund" -
                // creating any refund here would contradict the quote. The
                // payment stays CAPTURED; the money is the cancellation fee.
                log.info("Booking {} cancelled in the zero-refund window - keeping payment {} in full",
                        event.getBookingReference(), payment.paymentReference());
                return;
            }
            // A breakdown means some of the journey already FLEW: refund only
            // the upcoming rows' lines. No breakdown = legacy full refund of
            // the remaining capture.
            java.util.List<com.skybook.praveen.paymentservice.dto.request.FareLineRequest> lines =
                    event.getRefundBreakdown() != null && !event.getRefundBreakdown().isBlank()
                            ? com.skybook.praveen.paymentservice.domain.RefundCalculator
                                    .parse(event.getRefundBreakdown(), java.math.BigDecimal.ZERO).stream()
                                    .map(line -> new com.skybook.praveen.paymentservice.dto.request.FareLineRequest(
                                            line.fareType(), line.amount()))
                                    .toList()
                            : null;
            // One cause per whole-booking cancel: a booking cancels once, so a
            // second CANCELLED delivery is by definition a redelivery.
            String cause = "cancel:" + event.getBookingId();
            try {
                paymentFacade.refund(payment.id(),
                        new RefundRequest(lines, tierPercent, premiumPercent,
                                "Booking " + event.getBookingReference() + " cancelled", cause), ctx);
            } catch (org.springframework.dao.DataIntegrityViolationException alreadyRefunded) {
                log.info("Booking {} cancellation already refunded - redelivery, nothing to do",
                        event.getBookingReference());
            }
        } else if (status == PaymentStatus.PENDING || status == PaymentStatus.AUTHORIZED
                || status == PaymentStatus.AUTHORIZATION_FAILED || status == PaymentStatus.CAPTURE_FAILED) {
            paymentFacade.cancel(payment.id(), ctx);
        } else {
            log.info("Booking {} cancelled; payment {} already {} - no action",
                    event.getBookingReference(), payment.paymentReference(), status);
        }
    }
}
