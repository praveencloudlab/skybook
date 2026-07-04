package com.skybook.praveen.paymentservice.mapper;

import com.skybook.praveen.paymentservice.dto.response.RefundResponse;
import com.skybook.praveen.paymentservice.entity.Refund;

public final class RefundMapper {

    private RefundMapper() {
    }

    public static RefundResponse toResponse(Refund refund) {
        return new RefundResponse(
                refund.getId(),
                refund.getRefundReference(),
                refund.getPayment().getPaymentReference(),
                refund.getPayment().getBookingId(),
                refund.getPayment().getBookingReference(),
                refund.getAmount(),
                refund.getCancellationFee(),
                refund.getPayment().getCurrency(),
                refund.getReason(),
                refund.getStatus(),
                refund.getCompletedAt()
        );
    }
}
