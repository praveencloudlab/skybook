package com.skybook.praveen.checkinservice.dto.response;

import com.skybook.praveen.checkinservice.enums.CheckInStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CheckInResponse(

        Long id,

        Long bookingId,

        String bookingReference,

        Long bookingPassengerId,

        Long flightId,

        String flightNumber,

        String originAirportCode,

        String destinationAirportCode,

        LocalDateTime departureTime,

        String passengerName,

        String contactEmail,

        String seatNumber,

        String travelClass,

        String fareType,

        /** Seat surcharge PAID at booking - the free-seat-change ceiling (§9). Null on legacy rows => 0. */
        BigDecimal seatSurchargeEntitlement,

        String entitlementCurrency,

        CheckInStatus status,

        boolean documentVerified,

        LocalDateTime checkedInAt,

        LocalDateTime boardedAt,

        String gate,

        String boardingGroup,

        Long version,

        LocalDateTime createdAt,

        LocalDateTime updatedAt

) {
}
