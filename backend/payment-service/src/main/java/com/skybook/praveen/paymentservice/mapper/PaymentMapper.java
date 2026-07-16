package com.skybook.praveen.paymentservice.mapper;

import com.skybook.praveen.paymentservice.dto.response.PaymentResponse;
import com.skybook.praveen.paymentservice.entity.Payment;

public final class PaymentMapper {

    private PaymentMapper() {
    }

    /** Requires the transactions/refunds collections to be loaded - call inside the service transaction. */
    public static PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getPaymentReference(),
                payment.getBookingId(),
                payment.getBookingReference(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getCapturedAmount(),
                payment.getRefundedAmount(),
                payment.getStatus(),
                payment.getMethod(),
                payment.getGatewayReference(),
                payment.getFailureReason(),
                payment.getBaseFareTotal(),
                payment.getSeatSurchargeTotal(),
                payment.getTransactions().stream().map(PaymentTransactionMapper::toResponse).toList(),
                payment.getRefunds().stream().map(RefundMapper::toResponse).toList(),
                payment.getVersion(),
                payment.getCreatedAt(),
                payment.getUpdatedAt()
        );
    }
}
