package com.skybook.praveen.paymentservice.producer;

import com.skybook.praveen.common.constants.KafkaTopics;
import com.skybook.praveen.common.event.PaymentEvent;
import com.skybook.praveen.common.event.PaymentEventType;
import com.skybook.praveen.paymentservice.dto.response.PaymentResponse;
import com.skybook.praveen.paymentservice.dto.response.RefundResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Publishes PaymentEvent to skybook.payment.events. Called by PaymentFacade
 * AFTER the service transaction has committed - Sprint 6's booking-service
 * consumer confirms bookings off PAYMENT_SUCCEEDED.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventProducer {

    private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;

    public void publishPaymentSucceeded(PaymentResponse payment, String invoiceNumber) {
        publish(base(PaymentEventType.PAYMENT_SUCCEEDED, payment)
                .invoiceNumber(invoiceNumber)
                .build());
    }

    public void publishPaymentFailed(PaymentResponse payment) {
        publish(base(PaymentEventType.PAYMENT_FAILED, payment)
                .failureReason(payment.failureReason())
                .build());
    }

    public void publishPaymentCancelled(PaymentResponse payment) {
        publish(base(PaymentEventType.PAYMENT_CANCELLED, payment).build());
    }

    public void publishRefundCompleted(PaymentResponse payment, RefundResponse refund) {
        publish(base(PaymentEventType.REFUND_COMPLETED, payment)
                .refundedAmount(refund.amount())
                .cancellationFee(refund.cancellationFee())
                .build());
    }

    public void publishRefundFailed(PaymentResponse payment, RefundResponse refund) {
        publish(base(PaymentEventType.REFUND_FAILED, payment)
                .refundedAmount(refund.amount())
                .failureReason("Refund " + refund.refundReference() + " failed at gateway")
                .build());
    }

    private PaymentEvent.PaymentEventBuilder base(PaymentEventType type, PaymentResponse payment) {
        return PaymentEvent.builder()
                .type(type)
                .paymentReference(payment.paymentReference())
                .bookingId(payment.bookingId())
                .bookingReference(payment.bookingReference())
                .amount(payment.amount())
                .currency(payment.currency())
                .occurredAt(LocalDateTime.now());
    }

    private void publish(PaymentEvent event) {
        // Async but no longer fire-and-forget: broker-side failures now log
        // at ERROR into the centralized pipeline (RESILIENCE_MODULE.md §10).
        kafkaTemplate.send(KafkaTopics.PAYMENT_EVENTS, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish {} event for payment {} to {}",
                                event.getType(), event.getPaymentReference(), KafkaTopics.PAYMENT_EVENTS, ex);
                    }
                });
        log.info("Published {} event for payment {} (booking {})",
                event.getType(), event.getPaymentReference(), event.getBookingReference());
    }
}
