package com.skybook.praveen.paymentservice.domain;

import com.skybook.praveen.paymentservice.entity.Payment;
import com.skybook.praveen.paymentservice.enums.PaymentHistoryType;
import com.skybook.praveen.paymentservice.enums.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentStateMachineTest {

    private final PaymentStateMachine stateMachine = new PaymentStateMachine();

    private Payment paymentWith(PaymentStatus status) {
        return Payment.builder()
                .id(1L)
                .paymentReference("PAY-2026-TESTAA")
                .bookingId(42L)
                .bookingReference("SBTEST")
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .capturedAmount(BigDecimal.ZERO)
                .refundedAmount(BigDecimal.ZERO)
                .status(status)
                .build();
    }

    // The full golden transition table from design doc section 4.1 - every
    // (from, to) pair not listed here is expected to be invalid.
    private final Map<PaymentStatus, Set<PaymentStatus>> validTransitions = Map.of(
            PaymentStatus.PENDING, Set.of(PaymentStatus.AUTHORIZED,
                    PaymentStatus.AUTHORIZATION_FAILED, PaymentStatus.CANCELLED),
            PaymentStatus.AUTHORIZATION_FAILED, Set.of(PaymentStatus.PENDING, PaymentStatus.CANCELLED),
            PaymentStatus.AUTHORIZED, Set.of(PaymentStatus.CAPTURED,
                    PaymentStatus.CAPTURE_FAILED, PaymentStatus.CANCELLED),
            PaymentStatus.CAPTURE_FAILED, Set.of(PaymentStatus.CAPTURED, PaymentStatus.CANCELLED),
            PaymentStatus.CAPTURED, Set.of(PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED),
            PaymentStatus.PARTIALLY_REFUNDED, Set.of(PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED),
            PaymentStatus.REFUNDED, Set.of(),
            PaymentStatus.CANCELLED, Set.of()
    );

    @Test
    void matchesTheFullGoldenTransitionTable() {
        for (PaymentStatus from : PaymentStatus.values()) {
            for (PaymentStatus to : PaymentStatus.values()) {
                boolean expected = validTransitions.get(from).contains(to);
                assertThat(stateMachine.canTransition(from, to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void partiallyRefundedSelfLoopIsLegal() {
        // First self-loop transition in the project (design doc section 4.1).
        Payment payment = paymentWith(PaymentStatus.PARTIALLY_REFUNDED);

        stateMachine.transition(payment, PaymentStatus.PARTIALLY_REFUNDED,
                PaymentHistoryType.REFUND_COMPLETED, "USER", "API", "req-1", "second partial refund");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.getHistory()).hasSize(1);
    }

    @Test
    void transitionRecordsHistoryWithFullProvenance() {
        Payment payment = paymentWith(PaymentStatus.PENDING);

        stateMachine.transition(payment, PaymentStatus.AUTHORIZED, PaymentHistoryType.AUTHORIZED,
                "KAFKA", "BOOKING_EVENT", "SBTEST", "authorized at gateway");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.getHistory()).hasSize(1);
        var entry = payment.getHistory().getFirst();
        assertThat(entry.getHistoryType()).isEqualTo(PaymentHistoryType.AUTHORIZED);
        assertThat(entry.getActor()).isEqualTo("KAFKA");
        assertThat(entry.getSource()).isEqualTo("BOOKING_EVENT");
        assertThat(entry.getCorrelationId()).isEqualTo("SBTEST");
        assertThat(entry.getChangedAt()).isNotNull();
        assertThat(entry.getPayment()).isSameAs(payment);
    }

    @Test
    void invalidTransitionThrowsAndRecordsNothing() {
        Payment payment = paymentWith(PaymentStatus.CAPTURED);

        assertThatThrownBy(() -> stateMachine.transition(payment, PaymentStatus.AUTHORIZED,
                PaymentHistoryType.AUTHORIZED, "USER", "API", "req-1", "nope"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CAPTURED")
                .hasMessageContaining("AUTHORIZED");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.getHistory()).isEmpty();
    }

    @Test
    void terminalStatesHaveNoExits() {
        for (PaymentStatus terminal : Set.of(PaymentStatus.REFUNDED, PaymentStatus.CANCELLED)) {
            for (PaymentStatus to : PaymentStatus.values()) {
                assertThat(stateMachine.canTransition(terminal, to))
                        .as("%s -> %s must be illegal", terminal, to)
                        .isFalse();
            }
        }
    }

    @Test
    void recordHistoryIsUsableForNonTransitionEvents() {
        Payment payment = paymentWith(PaymentStatus.PENDING);

        stateMachine.recordHistory(payment, PaymentHistoryType.PAYMENT_CREATED,
                "USER", "API", "idem-key-1", "created");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING); // no transition
        assertThat(payment.getHistory()).hasSize(1);
    }
}
