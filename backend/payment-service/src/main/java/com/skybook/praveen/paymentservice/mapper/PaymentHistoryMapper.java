package com.skybook.praveen.paymentservice.mapper;

import com.skybook.praveen.paymentservice.dto.response.PaymentHistoryResponse;
import com.skybook.praveen.paymentservice.entity.PaymentHistory;

public final class PaymentHistoryMapper {

    private PaymentHistoryMapper() {
    }

    public static PaymentHistoryResponse toResponse(PaymentHistory history) {
        return new PaymentHistoryResponse(
                history.getId(),
                history.getHistoryType(),
                history.getActor(),
                history.getSource(),
                history.getCorrelationId(),
                history.getDetails(),
                history.getChangedAt()
        );
    }
}
