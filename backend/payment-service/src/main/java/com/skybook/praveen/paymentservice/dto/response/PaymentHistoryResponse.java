package com.skybook.praveen.paymentservice.dto.response;

import com.skybook.praveen.paymentservice.enums.PaymentHistoryType;

import java.time.LocalDateTime;

public record PaymentHistoryResponse(

        Long id,

        PaymentHistoryType historyType,

        String actor,

        String source,

        String correlationId,

        String details,

        LocalDateTime changedAt

) {
}
