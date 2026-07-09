package com.skybook.praveen.checkinservice.dto.response;

import com.skybook.praveen.checkinservice.enums.CheckInStatus;

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
