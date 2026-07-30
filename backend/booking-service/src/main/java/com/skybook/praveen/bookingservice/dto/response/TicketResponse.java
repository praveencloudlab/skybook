package com.skybook.praveen.bookingservice.dto.response;

import com.skybook.praveen.bookingservice.enums.CouponStatus;
import com.skybook.praveen.bookingservice.enums.TicketStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One traveller's e-ticket with its per-segment coupons
 * (ROUND_TRIP_MODULE.md, tickets & coupons). ticketNumber is the raw 13
 * digits; display convention is 125-XXXXXXXXXX.
 */
public record TicketResponse(

        Long id,

        String ticketNumber,

        Long passengerId,

        TicketStatus status,

        LocalDateTime issuedAt,

        List<TicketCouponResponse> coupons

) {

    public record TicketCouponResponse(

            int couponNumber,

            int segmentIndex,

            Long bookingPassengerId,

            CouponStatus status

    ) {
    }
}
