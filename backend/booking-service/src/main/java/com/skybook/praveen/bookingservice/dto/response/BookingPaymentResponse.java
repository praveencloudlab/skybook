package com.skybook.praveen.bookingservice.dto.response;

import com.skybook.praveen.bookingservice.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BookingPaymentResponse(

        PaymentStatus paymentStatus,

        BigDecimal amount,

        String currency,

        String externalPaymentReference,

        LocalDateTime paidAt

) {
}
