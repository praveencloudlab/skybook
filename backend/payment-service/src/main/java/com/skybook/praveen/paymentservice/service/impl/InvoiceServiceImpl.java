package com.skybook.praveen.paymentservice.service.impl;

import com.skybook.praveen.paymentservice.domain.InvoiceNumberGenerator;
import com.skybook.praveen.paymentservice.dto.response.InvoiceResponse;
import com.skybook.praveen.paymentservice.entity.Invoice;
import com.skybook.praveen.paymentservice.entity.Payment;
import com.skybook.praveen.paymentservice.exception.InvoiceNotFoundException;
import com.skybook.praveen.paymentservice.mapper.InvoiceMapper;
import com.skybook.praveen.paymentservice.repository.InvoiceRepository;
import com.skybook.praveen.paymentservice.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceServiceImpl implements InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceNumberGenerator invoiceNumberGenerator;

    @Override
    @Transactional(readOnly = true)
    public InvoiceResponse getByPaymentId(Long paymentId) {
        return InvoiceMapper.toResponse(invoiceRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new InvoiceNotFoundException(paymentId)));
    }

    /**
     * Called by PaymentServiceImpl.recordCaptureResult INSIDE the capture
     * transaction - the invoice exists iff the payment reached CAPTURED
     * (design doc sections 3.1.1 and 7). v1: tax = discount = 0,
     * subtotal = grandTotal = captured amount.
     */
    @Transactional
    public Invoice createForCapturedPayment(Payment payment) {

        Invoice invoice = Invoice.builder()
                .payment(payment)
                .invoiceNumber(invoiceNumberGenerator.next())
                .bookingReference(payment.getBookingReference())
                .subtotal(payment.getCapturedAmount())
                .taxAmount(BigDecimal.ZERO)
                .discount(BigDecimal.ZERO)
                .grandTotal(payment.getCapturedAmount())
                .currency(payment.getCurrency())
                .build();

        Invoice saved = invoiceRepository.save(invoice);
        log.info("Issued invoice {} for payment {} ({} {})",
                saved.getInvoiceNumber(), payment.getPaymentReference(),
                saved.getGrandTotal(), saved.getCurrency());

        return saved;
    }
}
