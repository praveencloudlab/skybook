package com.skybook.praveen.paymentservice.domain;

import com.skybook.praveen.paymentservice.entity.Payment;
import com.skybook.praveen.paymentservice.enums.PaymentMethod;
import com.skybook.praveen.paymentservice.enums.PaymentStatus;
import com.skybook.praveen.paymentservice.exception.PaymentConflictException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentValidatorTest {

    private final PaymentValidator validator = new PaymentValidator();

    private Payment payment(PaymentStatus status, String captured, String refunded) {
        return Payment.builder()
                .paymentReference("PAY-2026-TESTAA")
                .bookingId(42L)
                .amount(new BigDecimal("100.00"))
                .capturedAmount(new BigDecimal(captured))
                .refundedAmount(new BigDecimal(refunded))
                .status(status)
                .build();
    }

    @Test
    void amountMustBePositive() {
        assertThatCode(() -> validator.validateAmount(new BigDecimal("0.01"))).doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validateAmount(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateAmount(new BigDecimal("-5")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateAmount(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyImplementedMethodsPass() {
        assertThatCode(() -> validator.validateMethodImplemented(PaymentMethod.CARD)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validateMethodImplemented(null)).doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validateMethodImplemented(PaymentMethod.UPI))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UPI");
    }

    @Test
    void authorizableFromPendingAndFailedAuthorizationOnly() {
        assertThatCode(() -> validator.validateAuthorizable(payment(PaymentStatus.PENDING, "0", "0")))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateAuthorizable(payment(PaymentStatus.AUTHORIZATION_FAILED, "0", "0")))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validateAuthorizable(payment(PaymentStatus.CAPTURED, "100", "0")))
                .isInstanceOf(PaymentConflictException.class);
    }

    @Test
    void capturableFromAuthorizedAndFailedCaptureOnly() {
        assertThatCode(() -> validator.validateCapturable(payment(PaymentStatus.AUTHORIZED, "0", "0")))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateCapturable(payment(PaymentStatus.CAPTURE_FAILED, "0", "0")))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validateCapturable(payment(PaymentStatus.PENDING, "0", "0")))
                .isInstanceOf(PaymentConflictException.class);
    }

    @Test
    void capturedPaymentsCannotBeCancelledOnlyRefunded() {
        assertThatCode(() -> validator.validateCancellable(payment(PaymentStatus.PENDING, "0", "0")))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateCancellable(payment(PaymentStatus.AUTHORIZED, "0", "0")))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validateCancellable(payment(PaymentStatus.CAPTURED, "100", "0")))
                .isInstanceOf(PaymentConflictException.class)
                .hasMessageContaining("refund instead");
    }

    @Test
    void refundableOnlyFromCapturedStates() {
        assertThatCode(() -> validator.validateRefundable(
                payment(PaymentStatus.CAPTURED, "100.00", "0"), new BigDecimal("70.00")))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateRefundable(
                payment(PaymentStatus.PARTIALLY_REFUNDED, "100.00", "30.00"), new BigDecimal("40.00")))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validateRefundable(
                payment(PaymentStatus.AUTHORIZED, "0", "0"), BigDecimal.TEN))
                .isInstanceOf(PaymentConflictException.class);
    }

    @Test
    void refundCannotExceedRemainingCapturedAmount() {
        // Invariant: refundedAmount <= capturedAmount (design doc section 3.1.1).
        assertThatThrownBy(() -> validator.validateRefundable(
                payment(PaymentStatus.PARTIALLY_REFUNDED, "100.00", "70.00"), new BigDecimal("30.01")))
                .isInstanceOf(PaymentConflictException.class)
                .hasMessageContaining("exceeds");
    }
}
