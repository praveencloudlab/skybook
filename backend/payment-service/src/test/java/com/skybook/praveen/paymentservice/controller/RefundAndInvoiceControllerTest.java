package com.skybook.praveen.paymentservice.controller;

import com.skybook.praveen.paymentservice.config.WebSliceSecurityConfig;
import com.skybook.praveen.paymentservice.dto.response.InvoiceResponse;
import com.skybook.praveen.paymentservice.dto.response.RefundResponse;
import com.skybook.praveen.paymentservice.enums.RefundStatus;
import com.skybook.praveen.paymentservice.exception.InvoiceNotFoundException;
import com.skybook.praveen.paymentservice.exception.RefundNotFoundException;
import com.skybook.praveen.paymentservice.service.InvoiceService;
import com.skybook.praveen.paymentservice.service.PaymentService;
import com.skybook.praveen.paymentservice.service.RefundService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {RefundController.class, InvoiceController.class},
        excludeAutoConfiguration = com.skybook.praveen.security.JwtSecurityAutoConfiguration.class)
@Import(WebSliceSecurityConfig.class)
@WithMockUser(roles = "ADMIN")
class RefundAndInvoiceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RefundService refundService;

    @MockitoBean
    private InvoiceService invoiceService;

    @MockitoBean
    private PaymentService paymentService;

    private RefundResponse refund() {
        return new RefundResponse(5L, "REF-2026-L3Q9XE", "PAY-2026-K7M4Z9", 42L, "SBTEST",
                new BigDecimal("70.00"), new BigDecimal("30.00"), "USD",
                "booking cancelled", RefundStatus.COMPLETED, LocalDateTime.now());
    }

    @Test
    void listAndGetRefunds() throws Exception {
        when(refundService.getAllRefunds()).thenReturn(List.of(refund()));
        mockMvc.perform(get("/api/refunds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].refundReference").value("REF-2026-L3Q9XE"));

        when(refundService.getRefund(5L)).thenReturn(refund());
        mockMvc.perform(get("/api/refunds/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellationFee").value(30.00));
    }

    @Test
    void unknownRefundReturns404() throws Exception {
        when(refundService.getRefund(99L)).thenThrow(new RefundNotFoundException(99L));

        mockMvc.perform(get("/api/refunds/99")).andExpect(status().isNotFound());
    }

    @Test
    void invoiceByPaymentIdReturns200() throws Exception {
        when(invoiceService.getByPaymentId(1L)).thenReturn(new InvoiceResponse(
                7L, "INV-2026-000123", "PAY-2026-K7M4Z9", "SBTEST",
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("100.00"), "USD", null, null, LocalDateTime.now()));

        mockMvc.perform(get("/api/invoices/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceNumber").value("INV-2026-000123"))
                .andExpect(jsonPath("$.grandTotal").value(100.00));
    }

    @Test
    void uncapturedPaymentHasNoInvoice404WithExplanation() throws Exception {
        when(invoiceService.getByPaymentId(2L)).thenThrow(new InvoiceNotFoundException(2L));

        mockMvc.perform(get("/api/invoices/2"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(
                        "Invoice not found for payment id: 2 (invoices exist only after capture)"));
    }
}
