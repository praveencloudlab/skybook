package com.skybook.praveen.bookingservice.dto.response;

import com.skybook.praveen.bookingservice.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record BookingResponse(

        Long id,

        String bookingReference,

        Long customerId,

        Long flightId,

        BookingStatus bookingStatus,

        LocalDateTime bookingDate,

        BigDecimal totalFare,

        String remarks,

        String ownerSubject,

        List<BookingPassengerResponse> passengers,

        BookingContactResponse contact,

        BookingPaymentResponse payment,

        String createdBy,

        String updatedBy,

        Long version,

        LocalDateTime createdAt,

        LocalDateTime updatedAt

) {
}
