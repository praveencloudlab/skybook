package com.skybook.praveen.paymentservice.facade;

import com.skybook.praveen.paymentservice.client.GatewayResult;
import com.skybook.praveen.paymentservice.client.PaymentGatewayClient;
import com.skybook.praveen.paymentservice.dto.request.RefundRequest;
import com.skybook.praveen.paymentservice.dto.response.PaymentResponse;
import com.skybook.praveen.paymentservice.dto.response.RefundResponse;
import com.skybook.praveen.paymentservice.exception.GatewayDeclinedException;
import com.skybook.praveen.paymentservice.producer.PaymentEventProducer;
import com.skybook.praveen.paymentservice.service.ActionContext;
import com.skybook.praveen.paymentservice.service.InvoiceService;
import com.skybook.praveen.paymentservice.service.PaymentService;
import com.skybook.praveen.paymentservice.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ALL orchestration lives here (design doc section 2): begin (tx) ->
 * gateway call (deliberately OUTSIDE any DB transaction) -> record (tx) ->
 * publish (after commit). Deliberately NOT @Transactional.
 *
 * Gateway declines are recorded on the ledger and published as
 * PAYMENT_FAILED BEFORE the 422 GatewayDeclinedException is thrown - the
 * decline is a fact that happened, not an error to roll back.
 */
@Component
@RequiredArgsConstructor
public class PaymentFacade {

    private final PaymentService paymentService;
    private final RefundService refundService;
    private final InvoiceService invoiceService;
    private final PaymentGatewayClient gateway;
    private final PaymentEventProducer eventProducer;

    public PaymentResponse authorize(Long paymentId, ActionContext ctx) {

        // State-idempotent (IDEMPOTENCY_MODULE.md §3.5): the intent is "be
        // authorized". If it already is, the intent is satisfied - answer
        // with the payment instead of a conflict. The client this serves is
        // the one whose RESPONSE was lost: reporting an error on money that
        // moved provokes retries and support tickets over a success.
        PaymentResponse current = paymentService.getById(paymentId);
        if (current.status() == com.skybook.praveen.paymentservice.enums.PaymentStatus.AUTHORIZED) {
            return current;
        }

        PaymentService.AuthorizationContext auth = paymentService.beginAuthorize(paymentId);

        GatewayResult result = gateway.authorize(auth.paymentReference(), auth.amount(), auth.currency());

        PaymentResponse payment = paymentService.recordAuthorizationResult(paymentId, result, ctx);

        if (!result.success()) {
            eventProducer.publishPaymentFailed(payment);
            throw new GatewayDeclinedException("authorization", result.responseCode(), result.message());
        }
        return payment;
    }

    public PaymentResponse capture(Long paymentId, ActionContext ctx) {

        // Same §3.5 replay as authorize: a captured payment answers a repeat
        // capture with itself. Contradictory transitions (capture a CANCELLED
        // payment) still 409 via the validator - only the satisfied-intent
        // case replays.
        PaymentResponse current = paymentService.getById(paymentId);
        if (current.status() == com.skybook.praveen.paymentservice.enums.PaymentStatus.CAPTURED) {
            return current;
        }

        PaymentService.CaptureContext capture = paymentService.beginCapture(paymentId);

        GatewayResult result = gateway.capture(capture.gatewayReference(), capture.amount());

        PaymentResponse payment = paymentService.recordCaptureResult(paymentId, result, ctx);

        if (result.success()) {
            String invoiceNumber = invoiceService.getByPaymentId(paymentId).invoiceNumber();
            eventProducer.publishPaymentSucceeded(payment, invoiceNumber);
            return payment;
        }
        eventProducer.publishPaymentFailed(payment);
        throw new GatewayDeclinedException("capture", result.responseCode(), result.message());
    }

    public PaymentResponse cancel(Long paymentId, ActionContext ctx) {

        PaymentService.CancelContext cancel = paymentService.beginCancel(paymentId);

        GatewayResult voidResult = cancel.requiresVoid()
                ? gateway.voidAuthorization(cancel.gatewayReference())
                : null;

        PaymentResponse payment = paymentService.recordCancellation(paymentId, voidResult, ctx);

        eventProducer.publishPaymentCancelled(payment);
        return payment;
    }

    public RefundResponse refund(Long paymentId, RefundRequest request, ActionContext ctx) {

        RefundService.RefundContext refund = refundService.beginRefund(paymentId, request, ctx);

        GatewayResult result = gateway.refund(refund.gatewayReference(), refund.refundAmount());

        RefundResponse completed = refundService.completeRefund(refund.refundId(), result, ctx);

        PaymentResponse payment = paymentService.getById(paymentId);

        if (result.success()) {
            eventProducer.publishRefundCompleted(payment, completed);
        } else {
            eventProducer.publishRefundFailed(payment, completed);
        }
        return completed;
    }
}
