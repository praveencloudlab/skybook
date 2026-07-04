package com.skybook.praveen.paymentservice.service;

import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.paymentservice.client.GatewayResult;
import com.skybook.praveen.paymentservice.dto.request.CreatePaymentRequest;
import com.skybook.praveen.paymentservice.dto.response.PaymentHistoryResponse;
import com.skybook.praveen.paymentservice.dto.response.PaymentResponse;

import java.math.BigDecimal;
import java.util.List;

/**
 * Owns the Payment aggregate. The begin/record method pairs implement the
 * facade's gateway-outside-transaction flow (design doc section 2):
 * the begin methods validate and return what the gateway call needs
 * (read-only tx); the record methods apply the outcome - transition,
 * ledger row, invoice - in one write transaction.
 */
public interface PaymentService {

    /** replay = true when the idempotency key matched an existing payment (return 200, not 201). */
    record CreationResult(PaymentResponse payment, boolean replay) {
    }

    record AuthorizationContext(Long paymentId, String paymentReference, BigDecimal amount, String currency) {
    }

    record CaptureContext(Long paymentId, String gatewayReference, BigDecimal amount) {
    }

    /** requiresVoid = the payment is AUTHORIZED, so cancelling means a gateway VOID first. */
    record CancelContext(Long paymentId, boolean requiresVoid, String gatewayReference) {
    }

    CreationResult create(CreatePaymentRequest request, String idempotencyKey);

    /** Consumer path - idempotent by bookingId: a duplicate event returns the existing payment. */
    PaymentResponse createFromBookingEvent(BookingEvent event);

    PaymentResponse getById(Long id);

    PaymentResponse getByReference(String paymentReference);

    PaymentResponse getByBookingId(Long bookingId);

    List<PaymentHistoryResponse> getHistory(Long paymentId);

    AuthorizationContext beginAuthorize(Long id);

    PaymentResponse recordAuthorizationResult(Long id, GatewayResult result, ActionContext ctx);

    CaptureContext beginCapture(Long id);

    PaymentResponse recordCaptureResult(Long id, GatewayResult result, ActionContext ctx);

    CancelContext beginCancel(Long id);

    /** voidResult is null when the payment was never authorized (nothing to void at the gateway). */
    PaymentResponse recordCancellation(Long id, GatewayResult voidResult, ActionContext ctx);
}
