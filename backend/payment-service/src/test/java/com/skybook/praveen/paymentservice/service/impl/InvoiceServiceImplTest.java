package com.skybook.praveen.paymentservice.service.impl;

import com.skybook.praveen.paymentservice.domain.InvoiceNumberGenerator;
import com.skybook.praveen.paymentservice.entity.Invoice;
import com.skybook.praveen.paymentservice.entity.Payment;
import com.skybook.praveen.paymentservice.enums.PaymentStatus;
import com.skybook.praveen.paymentservice.exception.InvoiceNotFoundException;
import com.skybook.praveen.paymentservice.repository.InvoiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceImplTest {

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private InvoiceNumberGenerator invoiceNumberGenerator;

    private InvoiceServiceImpl invoiceService;

    @BeforeEach
    void setUp() {
        invoiceService = new InvoiceServiceImpl(invoiceRepository, invoiceNumberGenerator);
    }

    @Test
    void createSnapshotsTheCapturedPaymentWithV1MoneyBreakdown() {
        Payment payment = Payment.builder()
                .id(1L).paymentReference("PAY-2026-TESTAA")
                .bookingId(42L).bookingReference("SBTEST")
                .amount(new BigDecimal("180.00")).currency("USD")
                .capturedAmount(new BigDecimal("180.00")).refundedAmount(BigDecimal.ZERO)
                .status(PaymentStatus.CAPTURED)
                .build();
        when(invoiceNumberGenerator.next()).thenReturn("INV-2026-000042");
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        invoiceService.createForCapturedPayment(payment);

        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceRepository).save(captor.capture());
        Invoice invoice = captor.getValue();
        assertThat(invoice.getInvoiceNumber()).isEqualTo("INV-2026-000042");
        assertThat(invoice.getBookingReference()).isEqualTo("SBTEST");
        assertThat(invoice.getSubtotal()).isEqualByComparingTo("180.00");
        assertThat(invoice.getTaxAmount()).isEqualByComparingTo("0");
        assertThat(invoice.getDiscount()).isEqualByComparingTo("0");
        assertThat(invoice.getGrandTotal()).isEqualByComparingTo("180.00");
    }

    @Test
    void missingInvoiceExplainsThatInvoicesExistOnlyAfterCapture() {
        when(invoiceRepository.findByPaymentId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> invoiceService.getByPaymentId(1L))
                .isInstanceOf(InvoiceNotFoundException.class)
                .hasMessageContaining("after capture");
    }
}
