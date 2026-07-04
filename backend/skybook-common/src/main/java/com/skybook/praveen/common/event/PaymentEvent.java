package com.skybook.praveen.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Published by payment-service on skybook.payment.events. Sprint 6:
 * booking-service consumes PAYMENT_SUCCEEDED to confirm bookings.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentEvent {

    private PaymentEventType type;

    /** e.g. "PAY-2026-K7M4Z9" */
    private String paymentReference;

    private Long bookingId;

    /** PNR */
    private String bookingReference;

    private BigDecimal amount;

    private String currency;

    /** Refund events only */
    private BigDecimal refundedAmount;

    /** Refund events only - the withheld portion */
    private BigDecimal cancellationFee;

    /** Failure events only - gateway message */
    private String failureReason;

    /** PAYMENT_SUCCEEDED only */
    private String invoiceNumber;

    private LocalDateTime occurredAt;
}
