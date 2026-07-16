package com.skybook.praveen.paymentservice.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record InvoiceResponse(

        Long id,

        String invoiceNumber,

        String paymentReference,

        String bookingReference,

        BigDecimal subtotal,

        BigDecimal taxAmount,

        BigDecimal discount,

        BigDecimal grandTotal,

        String currency,

        /** §10 charge composition, snapshotted from the payment. Null on legacy payments. */
        BigDecimal baseFareTotal,

        BigDecimal seatSurchargeTotal,

        LocalDateTime issuedAt

) {
}
