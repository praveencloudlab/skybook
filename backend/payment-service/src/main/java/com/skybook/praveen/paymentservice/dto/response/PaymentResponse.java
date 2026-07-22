package com.skybook.praveen.paymentservice.dto.response;

import com.skybook.praveen.paymentservice.enums.PaymentMethod;
import com.skybook.praveen.paymentservice.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record PaymentResponse(

        Long id,

        String paymentReference,

        Long bookingId,

        String bookingReference,

        BigDecimal amount,

        String currency,

        BigDecimal capturedAmount,

        BigDecimal refundedAmount,

        PaymentStatus status,

        PaymentMethod method,

        String gatewayReference,

        String failureReason,

        /** §10 charge composition: sum of base fares / of CHARGED seat surcharges. Null on legacy payments. */
        BigDecimal baseFareTotal,

        BigDecimal seatSurchargeTotal,

        /** JWT subject of the booking owner (§4.2), snapshotted from the event. Null on legacy rows. */
        String ownerSubject,

        List<PaymentTransactionResponse> transactions,

        List<RefundResponse> refunds,

        Long version,

        LocalDateTime createdAt,

        LocalDateTime updatedAt

) {
}
