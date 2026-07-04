package com.skybook.praveen.paymentservice.controller;

import com.skybook.praveen.paymentservice.config.SecurityConfig;
import com.skybook.praveen.paymentservice.dto.request.CreatePaymentRequest;
import com.skybook.praveen.paymentservice.dto.request.RefundRequest;
import com.skybook.praveen.paymentservice.dto.response.PaymentResponse;
import com.skybook.praveen.paymentservice.dto.response.RefundResponse;
import com.skybook.praveen.paymentservice.enums.PaymentMethod;
import com.skybook.praveen.paymentservice.enums.PaymentStatus;
import com.skybook.praveen.paymentservice.enums.RefundStatus;
import com.skybook.praveen.paymentservice.exception.GatewayDeclinedException;
import com.skybook.praveen.paymentservice.exception.PaymentConflictException;
import com.skybook.praveen.paymentservice.exception.PaymentNotFoundException;
import com.skybook.praveen.paymentservice.facade.PaymentFacade;
import com.skybook.praveen.paymentservice.service.ActionContext;
import com.skybook.praveen.paymentservice.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@Import(SecurityConfig.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private PaymentFacade paymentFacade;

    private PaymentResponse response(PaymentStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return new PaymentResponse(1L, "PAY-2026-K7M4Z9", 42L, "SBTEST",
                new BigDecimal("100.00"), "USD",
                status == PaymentStatus.CAPTURED ? new BigDecimal("100.00") : BigDecimal.ZERO,
                BigDecimal.ZERO, status, PaymentMethod.CARD,
                "SIM-abc", null, List.of(), List.of(), 0L, now, now);
    }

    private static final String CREATE_BODY = """
            {"bookingId":42,"bookingReference":"SBTEST","amount":100.00,"currency":"USD"}
            """;

    @Test
    void createReturns201() throws Exception {
        when(paymentService.create(any(CreatePaymentRequest.class), isNull()))
                .thenReturn(new PaymentService.CreationResult(response(PaymentStatus.PENDING), false));

        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentReference").value("PAY-2026-K7M4Z9"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void idempotencyKeyReplayReturns200WithTheOriginal() throws Exception {
        when(paymentService.create(any(CreatePaymentRequest.class), eq("idem-1")))
                .thenReturn(new PaymentService.CreationResult(response(PaymentStatus.PENDING), true));

        mockMvc.perform(post("/api/payments")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentReference").value("PAY-2026-K7M4Z9"));
    }

    @Test
    void missingBookingIdReturns400() throws Exception {
        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookingReference":"SBTEST","amount":100.00,"currency":"USD"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateBookingReturns409() throws Exception {
        when(paymentService.create(any(CreatePaymentRequest.class), isNull()))
                .thenThrow(new PaymentConflictException("Payment already exists for booking id: 42"));

        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void lookupsReturn200And404() throws Exception {
        when(paymentService.getById(1L)).thenReturn(response(PaymentStatus.PENDING));
        mockMvc.perform(get("/api/payments/1")).andExpect(status().isOk());

        when(paymentService.getByReference("PAY-2026-K7M4Z9")).thenReturn(response(PaymentStatus.PENDING));
        mockMvc.perform(get("/api/payments/reference/PAY-2026-K7M4Z9")).andExpect(status().isOk());

        when(paymentService.getByBookingId(99L)).thenThrow(PaymentNotFoundException.byBooking(99L));
        mockMvc.perform(get("/api/payments/booking/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Payment not found for booking id: 99"));
    }

    @Test
    void authorizeReturns200() throws Exception {
        when(paymentFacade.authorize(eq(1L), any(ActionContext.class)))
                .thenReturn(response(PaymentStatus.AUTHORIZED));

        mockMvc.perform(patch("/api/payments/1/authorize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
    }

    @Test
    void gatewayDeclineReturns422() throws Exception {
        when(paymentFacade.authorize(eq(1L), any(ActionContext.class)))
                .thenThrow(new GatewayDeclinedException("authorization", "SIM_DECLINED", "Card declined"));

        mockMvc.perform(patch("/api/payments/1/authorize"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        "Gateway declined authorization [SIM_DECLINED]: Card declined"));
    }

    @Test
    void captureInWrongStateReturns409() throws Exception {
        when(paymentFacade.capture(eq(1L), any(ActionContext.class)))
                .thenThrow(new PaymentConflictException("Payment PAY-2026-K7M4Z9 is PENDING - cannot capture"));

        mockMvc.perform(patch("/api/payments/1/capture"))
                .andExpect(status().isConflict());
    }

    @Test
    void refundWithoutABodyWorks() throws Exception {
        RefundResponse refund = new RefundResponse(5L, "REF-2026-L3Q9XE", "PAY-2026-K7M4Z9",
                42L, "SBTEST", new BigDecimal("70.00"), new BigDecimal("30.00"), "USD",
                null, RefundStatus.COMPLETED, LocalDateTime.now());
        when(paymentFacade.refund(eq(1L), any(RefundRequest.class), any(ActionContext.class)))
                .thenReturn(refund);

        mockMvc.perform(patch("/api/payments/1/refund"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(70.00))
                .andExpect(jsonPath("$.cancellationFee").value(30.00));
    }

    @Test
    void cancelReturns200() throws Exception {
        when(paymentFacade.cancel(eq(1L), any(ActionContext.class)))
                .thenReturn(response(PaymentStatus.CANCELLED));

        mockMvc.perform(patch("/api/payments/1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
