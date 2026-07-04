package com.skybook.praveen.paymentservice.dto.response;

import com.skybook.praveen.paymentservice.enums.TransactionStatus;
import com.skybook.praveen.paymentservice.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentTransactionResponse(

        Long id,

        String transactionReference,

        TransactionType type,

        TransactionStatus status,

        BigDecimal amount,

        String gatewayReference,

        String gatewayResponseCode,

        String gatewayMessage,

        Long durationMs,

        LocalDateTime occurredAt

) {
}
