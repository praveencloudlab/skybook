package com.skybook.praveen.paymentservice.mapper;

import com.skybook.praveen.paymentservice.dto.response.InvoiceResponse;
import com.skybook.praveen.paymentservice.entity.Invoice;

public final class InvoiceMapper {

    private InvoiceMapper() {
    }

    public static InvoiceResponse toResponse(Invoice invoice) {
        return new InvoiceResponse(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getPayment().getPaymentReference(),
                invoice.getBookingReference(),
                invoice.getSubtotal(),
                invoice.getTaxAmount(),
                invoice.getDiscount(),
                invoice.getGrandTotal(),
                invoice.getCurrency(),
                invoice.getBaseFareTotal(),
                invoice.getSeatSurchargeTotal(),
                invoice.getIssuedAt()
        );
    }
}
