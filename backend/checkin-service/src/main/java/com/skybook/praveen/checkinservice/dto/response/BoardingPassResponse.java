package com.skybook.praveen.checkinservice.dto.response;

import com.skybook.praveen.checkinservice.enums.BoardingPassStatus;

import java.time.LocalDateTime;

public record BoardingPassResponse(

        Long id,

        Long checkInId,

        String boardingPassNumber,

        String token,

        String passengerName,

        String bookingReference,

        String flightNumber,

        String originAirportCode,

        String destinationAirportCode,

        String seatNumber,

        /** Departure terminal printed on the pass; null on pre-terminal check-ins. */
        String departureTerminal,

        String gate,

        LocalDateTime boardingTime,

        String boardingGroup,

        BoardingPassStatus status,

        LocalDateTime issuedAt,

        LocalDateTime revokedAt,

        Long reissuedAsId

) {
}
