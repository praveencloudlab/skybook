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

        List<PaymentTransactionResponse> transactions,

        List<RefundResponse> refunds,

        Long version,

        LocalDateTime createdAt,

        LocalDateTime updatedAt

) {
}
