package com.skybook.praveen.paymentservice.facade;

import com.skybook.praveen.paymentservice.client.GatewayResult;
import com.skybook.praveen.paymentservice.client.PaymentGatewayClient;
import com.skybook.praveen.paymentservice.dto.request.RefundRequest;
import com.skybook.praveen.paymentservice.dto.response.InvoiceResponse;
import com.skybook.praveen.paymentservice.dto.response.PaymentResponse;
import com.skybook.praveen.paymentservice.dto.response.RefundResponse;
import com.skybook.praveen.paymentservice.enums.PaymentMethod;
import com.skybook.praveen.paymentservice.enums.PaymentStatus;
import com.skybook.praveen.paymentservice.enums.RefundStatus;
import com.skybook.praveen.paymentservice.exception.GatewayDeclinedException;
import com.skybook.praveen.paymentservice.producer.PaymentEventProducer;
import com.skybook.praveen.paymentservice.service.ActionContext;
import com.skybook.praveen.paymentservice.service.InvoiceService;
import com.skybook.praveen.paymentservice.service.PaymentService;
import com.skybook.praveen.paymentservice.service.RefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentFacadeTest {

    private static final ActionContext CTX = ActionContext.user("req-1");

    @Mock
    private PaymentService paymentService;
    @Mock
    private RefundService refundService;
    @Mock
    private InvoiceService invoiceService;
    @Mock
    private PaymentGatewayClient gateway;
    @Mock
    private PaymentEventProducer eventProducer;

    private PaymentFacade facade;

    private final LocalDateTime now = LocalDateTime.now();

    @BeforeEach
    void setUp() {
        facade = new PaymentFacade(paymentService, refundService, invoiceService, gateway, eventProducer);
    }

    private PaymentResponse payment(PaymentStatus status) {
        return new PaymentResponse(1L, "PAY-2026-TESTAA", 42L, "SBTEST",
                new BigDecimal("100.00"), "USD",
                status == PaymentStatus.CAPTURED ? new BigDecimal("100.00") : BigDecimal.ZERO,
                BigDecimal.ZERO, status, PaymentMethod.CARD, "SIM-ref",
                status == PaymentStatus.AUTHORIZATION_FAILED ? "declined" : null,
                null, null, "owner@test.com", List.of(), List.of(), 0L, now, now);
    }

    private GatewayResult success() {
        return GatewayResult.simulated(true, "SIM-ref", "SIM_OK", "ok", new BigDecimal("100.00"), 3);
    }

    private GatewayResult declined() {
        return GatewayResult.simulated(false, null, "SIM_DECLINED", "declined", new BigDecimal("100.00"), 3);
    }

    @Test
    void authorizeRunsBeginGatewayRecordInOrder() {
        when(paymentService.beginAuthorize(1L)).thenReturn(
                new PaymentService.AuthorizationContext(1L, "PAY-2026-TESTAA", new BigDecimal("100.00"), "USD"));
        when(gateway.authorize("PAY-2026-TESTAA", new BigDecimal("100.00"), "USD")).thenReturn(success());
        when(paymentService.recordAuthorizationResult(eq(1L), any(), eq(CTX)))
                .thenReturn(payment(PaymentStatus.AUTHORIZED));

        facade.authorize(1L, CTX);

        InOrder order = inOrder(paymentService, gateway);
        order.verify(paymentService).beginAuthorize(1L);
        order.verify(gateway).authorize("PAY-2026-TESTAA", new BigDecimal("100.00"), "USD");
        order.verify(paymentService).recordAuthorizationResult(eq(1L), any(), eq(CTX));
        verifyNoInteractions(eventProducer); // success authorize publishes nothing (capture does)
    }

    @Test
    void declinedAuthorizationIsRecordedPublishedThenThrown422() {
        when(paymentService.beginAuthorize(1L)).thenReturn(
                new PaymentService.AuthorizationContext(1L, "PAY-2026-TESTAA", new BigDecimal("100.00"), "USD"));
        when(gateway.authorize(any(), any(), any())).thenReturn(declined());
        PaymentResponse failed = payment(PaymentStatus.AUTHORIZATION_FAILED);
        when(paymentService.recordAuthorizationResult(eq(1L), any(), eq(CTX))).thenReturn(failed);

        assertThatThrownBy(() -> facade.authorize(1L, CTX))
                .isInstanceOf(GatewayDeclinedException.class)
                .hasMessageContaining("SIM_DECLINED");

        // The decline was recorded AND published before the throw.
        verify(paymentService).recordAuthorizationResult(eq(1L), any(), eq(CTX));
        verify(eventProducer).publishPaymentFailed(failed);
    }

    @Test
    void captureSuccessPublishesPaymentSucceededWithTheInvoiceNumber() {
        when(paymentService.beginCapture(1L)).thenReturn(
                new PaymentService.CaptureContext(1L, "SIM-ref", new BigDecimal("100.00")));
        when(gateway.capture("SIM-ref", new BigDecimal("100.00"))).thenReturn(success());
        PaymentResponse captured = payment(PaymentStatus.CAPTURED);
        when(paymentService.recordCaptureResult(eq(1L), any(), eq(CTX))).thenReturn(captured);
        when(invoiceService.getByPaymentId(1L)).thenReturn(new InvoiceResponse(
                5L, "INV-2026-000001", "PAY-2026-TESTAA", "SBTEST",
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("100.00"), "USD", null, null, now));

        facade.capture(1L, CTX);

        verify(eventProducer).publishPaymentSucceeded(captured, "INV-2026-000001");
    }

    @Test
    void cancelOfAuthorizedPaymentVoidsAtTheGateway() {
        when(paymentService.beginCancel(1L)).thenReturn(
                new PaymentService.CancelContext(1L, true, "SIM-ref"));
        when(gateway.voidAuthorization("SIM-ref")).thenReturn(success());
        PaymentResponse cancelled = payment(PaymentStatus.CANCELLED);
        when(paymentService.recordCancellation(eq(1L), any(), eq(CTX))).thenReturn(cancelled);

        facade.cancel(1L, CTX);

        verify(gateway).voidAuthorization("SIM-ref");
        verify(eventProducer).publishPaymentCancelled(cancelled);
    }

    @Test
    void cancelOfPendingPaymentSkipsTheGatewayEntirely() {
        when(paymentService.beginCancel(1L)).thenReturn(
                new PaymentService.CancelContext(1L, false, null));
        when(paymentService.recordCancellation(eq(1L), eq(null), eq(CTX)))
                .thenReturn(payment(PaymentStatus.CANCELLED));

        facade.cancel(1L, CTX);

        verifyNoInteractions(gateway);
    }

    @Test
    void refundSuccessPublishesRefundCompleted() {
        RefundRequest request = new RefundRequest(null, "booking cancelled");
        when(refundService.beginRefund(1L, request, CTX)).thenReturn(
                new RefundService.RefundContext(77L, "SIM-ref", new BigDecimal("70.00")));
        when(gateway.refund("SIM-ref", new BigDecimal("70.00"))).thenReturn(success());
        RefundResponse completed = new RefundResponse(77L, "REF-2026-TESTAA", "PAY-2026-TESTAA",
                42L, "SBTEST", new BigDecimal("70.00"), new BigDecimal("30.00"), "USD",
                "booking cancelled", RefundStatus.COMPLETED, now);
        when(refundService.completeRefund(eq(77L), any(), eq(CTX))).thenReturn(completed);
        PaymentResponse refunded = payment(PaymentStatus.CAPTURED);
        when(paymentService.getById(1L)).thenReturn(refunded);

        RefundResponse response = facade.refund(1L, request, CTX);

        assertThat(response.status()).isEqualTo(RefundStatus.COMPLETED);
        verify(eventProducer).publishRefundCompleted(refunded, completed);
    }
}
