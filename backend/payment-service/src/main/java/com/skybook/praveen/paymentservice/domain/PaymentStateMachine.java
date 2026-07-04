package com.skybook.praveen.paymentservice.domain;

import com.skybook.praveen.paymentservice.entity.Payment;
import com.skybook.praveen.paymentservice.entity.PaymentHistory;
import com.skybook.praveen.paymentservice.enums.PaymentHistoryType;
import com.skybook.praveen.paymentservice.enums.PaymentStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Validates and applies PaymentStatus transitions (design doc section 4.1)
 * and records every transition onto Payment.history in-memory - persisted
 * via cascade when the caller saves. Same design as the Booking/Inventory
 * state machines; the provenance fields (actor/source/correlationId) are
 * mandatory on every history entry.
 */
@Component
public class PaymentStateMachine {

    private static final Map<PaymentStatus, Set<PaymentStatus>> TRANSITIONS = new EnumMap<>(PaymentStatus.class);

    static {
        TRANSITIONS.put(PaymentStatus.PENDING,
                EnumSet.of(PaymentStatus.AUTHORIZED, PaymentStatus.AUTHORIZATION_FAILED, PaymentStatus.CANCELLED));
        TRANSITIONS.put(PaymentStatus.AUTHORIZATION_FAILED,
                EnumSet.of(PaymentStatus.PENDING, PaymentStatus.CANCELLED));
        TRANSITIONS.put(PaymentStatus.AUTHORIZED,
                EnumSet.of(PaymentStatus.CAPTURED, PaymentStatus.CAPTURE_FAILED, PaymentStatus.CANCELLED));
        TRANSITIONS.put(PaymentStatus.CAPTURE_FAILED,
                EnumSet.of(PaymentStatus.CAPTURED, PaymentStatus.CANCELLED));
        TRANSITIONS.put(PaymentStatus.CAPTURED,
                EnumSet.of(PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED));
        // Self-loop: multiple partial refunds are legal (design doc section 4.1).
        TRANSITIONS.put(PaymentStatus.PARTIALLY_REFUNDED,
                EnumSet.of(PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED));
        TRANSITIONS.put(PaymentStatus.REFUNDED, EnumSet.noneOf(PaymentStatus.class));
        TRANSITIONS.put(PaymentStatus.CANCELLED, EnumSet.noneOf(PaymentStatus.class));
    }

    public boolean canTransition(PaymentStatus from, PaymentStatus to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public void transition(Payment payment, PaymentStatus to, PaymentHistoryType historyType,
                           String actor, String source, String correlationId, String details) {

        PaymentStatus from = payment.getStatus();

        if (!canTransition(from, to)) {
            throw new IllegalStateException("Cannot transition payment "
                    + payment.getPaymentReference() + " from " + from + " to " + to);
        }

        payment.setStatus(to);
        recordHistory(payment, historyType, actor, source, correlationId, details);
    }

    /** Also used directly by services for non-transition events (PAYMENT_CREATED, REFUND_REQUESTED). */
    public void recordHistory(Payment payment, PaymentHistoryType type,
                              String actor, String source, String correlationId, String details) {

        PaymentHistory entry = PaymentHistory.builder()
                .payment(payment)
                .historyType(type)
                .actor(actor)
                .source(source)
                .correlationId(correlationId)
                .details(details)
                .changedAt(LocalDateTime.now())
                .build();

        payment.getHistory().add(entry);
    }
}
