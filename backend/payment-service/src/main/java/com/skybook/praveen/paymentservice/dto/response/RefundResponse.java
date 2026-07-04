package com.skybook.praveen.paymentservice.dto.response;

import com.skybook.praveen.paymentservice.enums.RefundStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record RefundResponse(

        Long id,

        String refundReference,

        String paymentReference,

        Long bookingId,

        String bookingReference,

        BigDecimal amount,

        BigDecimal cancellationFee,

        String currency,

        String reason,

        RefundStatus status,

        LocalDateTime completedAt

) {
}
