package com.skybook.praveen.bookingservice.dto.response;

import com.skybook.praveen.bookingservice.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record BookingResponse(

        Long id,

        String bookingReference,

        Long customerId,

        // DEPRECATED: segment 0's flight - read segments instead.
        Long flightId,

        // The journey's legs in order (ROUND_TRIP_MODULE.md §4).
        List<BookingSegmentResponse> segments,

        BookingStatus bookingStatus,

        LocalDateTime bookingDate,

        BigDecimal totalFare,

        String remarks,

        String ownerSubject,

        List<BookingPassengerResponse> passengers,

        BookingContactResponse contact,

        BookingPaymentResponse payment,

        // E-tickets, one per traveller with a coupon per segment - empty
        // until the booking is CONFIRMED (issued on payment capture).
        List<TicketResponse> tickets,

        String createdBy,

        String updatedBy,

        Long version,

        LocalDateTime createdAt,

        LocalDateTime updatedAt

) {
}
