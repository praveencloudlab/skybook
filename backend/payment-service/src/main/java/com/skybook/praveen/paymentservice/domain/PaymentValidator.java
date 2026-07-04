package com.skybook.praveen.paymentservice.domain;

import com.skybook.praveen.paymentservice.entity.Payment;
import com.skybook.praveen.paymentservice.enums.PaymentMethod;
import com.skybook.praveen.paymentservice.enums.PaymentStatus;
import com.skybook.praveen.paymentservice.exception.PaymentConflictException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Guard clauses for command flows - throws with precise reasons
 * (design doc sections 3.1.1 and 12). State-transition legality itself is
 * the state machine's job; this class checks everything else.
 */
@Component
public class PaymentValidator {

    private static final Set<PaymentMethod> IMPLEMENTED_METHODS = Set.of(PaymentMethod.CARD);

    public void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }
    }

    /** Enum accepts the full vocabulary; only implemented methods pass (design doc section 4.4). */
    public void validateMethodImplemented(PaymentMethod method) {
        if (method != null && !IMPLEMENTED_METHODS.contains(method)) {
            throw new IllegalArgumentException("Payment method " + method
                    + " is not implemented yet - supported: " + IMPLEMENTED_METHODS);
        }
    }

    public void validateAuthorizable(Payment payment) {
        if (payment.getStatus() != PaymentStatus.PENDING
                && payment.getStatus() != PaymentStatus.AUTHORIZATION_FAILED) {
            throw new PaymentConflictException("Payment " + payment.getPaymentReference()
                    + " is " + payment.getStatus() + " - cannot authorize");
        }
    }

    public void validateCapturable(Payment payment) {
        if (payment.getStatus() != PaymentStatus.AUTHORIZED
                && payment.getStatus() != PaymentStatus.CAPTURE_FAILED) {
            throw new PaymentConflictException("Payment " + payment.getPaymentReference()
                    + " is " + payment.getStatus() + " - cannot capture");
        }
    }

    public void validateCancellable(Payment payment) {
        if (payment.getStatus() != PaymentStatus.PENDING
                && payment.getStatus() != PaymentStatus.AUTHORIZED
                && payment.getStatus() != PaymentStatus.AUTHORIZATION_FAILED
                && payment.getStatus() != PaymentStatus.CAPTURE_FAILED) {
            throw new PaymentConflictException("Payment " + payment.getPaymentReference()
                    + " is " + payment.getStatus() + " - cannot cancel (refund instead if captured)");
        }
    }

    public void validateRefundable(Payment payment, BigDecimal refundAmount) {
        if (payment.getStatus() != PaymentStatus.CAPTURED
                && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new PaymentConflictException("Payment " + payment.getPaymentReference()
                    + " is " + payment.getStatus() + " - only captured payments can be refunded");
        }

        BigDecimal remaining = payment.getCapturedAmount().subtract(payment.getRefundedAmount());
        // Invariant: refundedAmount <= capturedAmount (design doc section 3.1.1).
        if (refundAmount.compareTo(remaining) > 0) {
            throw new PaymentConflictException("Refund of " + refundAmount
                    + " exceeds the remaining captured amount of " + remaining
                    + " on payment " + payment.getPaymentReference());
        }
    }
}
