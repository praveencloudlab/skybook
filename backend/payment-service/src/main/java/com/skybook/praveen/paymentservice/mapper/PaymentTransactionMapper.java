package com.skybook.praveen.paymentservice.mapper;

import com.skybook.praveen.paymentservice.dto.response.PaymentTransactionResponse;
import com.skybook.praveen.paymentservice.entity.PaymentTransaction;

public final class PaymentTransactionMapper {

    private PaymentTransactionMapper() {
    }

    public static PaymentTransactionResponse toResponse(PaymentTransaction transaction) {
        return new PaymentTransactionResponse(
                transaction.getId(),
                transaction.getTransactionReference(),
                transaction.getType(),
                transaction.getStatus(),
                transaction.getAmount(),
                transaction.getGatewayReference(),
                transaction.getGatewayResponseCode(),
                transaction.getGatewayMessage(),
                transaction.getDurationMs(),
                transaction.getOccurredAt()
        );
    }
}
