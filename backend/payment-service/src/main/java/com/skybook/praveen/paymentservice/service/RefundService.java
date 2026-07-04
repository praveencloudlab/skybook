package com.skybook.praveen.paymentservice.service;

import com.skybook.praveen.paymentservice.client.GatewayResult;
import com.skybook.praveen.paymentservice.dto.request.RefundRequest;
import com.skybook.praveen.paymentservice.dto.response.RefundResponse;

import java.math.BigDecimal;
import java.util.List;

/** Owns Refund rows. Same begin/complete split as PaymentService for the gateway-outside-tx flow. */
public interface RefundService {

    record RefundContext(Long refundId, String gatewayReference, BigDecimal refundAmount) {
    }

    /**
     * Validates, computes amounts via RefundCalculator, creates a PENDING
     * Refund row + REFUND_REQUESTED history. The gateway call happens after
     * this transaction commits.
     */
    RefundContext beginRefund(Long paymentId, RefundRequest request, ActionContext ctx);

    /** Applies the gateway outcome: COMPLETED (+ payment totals + transition) or FAILED. */
    RefundResponse completeRefund(Long refundId, GatewayResult result, ActionContext ctx);

    List<RefundResponse> getAllRefunds();

    RefundResponse getRefund(Long id);
}
