package com.skybook.praveen.inventoryservice.dto.response;

import com.skybook.praveen.inventoryservice.enums.SeatReservationStatus;

import java.time.LocalDateTime;

public record SeatReservationResponse(

        Long id,

        Long flightId,

        Long aircraftSeatId,

        String seatNumber,

        Long bookingId,

        Long bookingPassengerId,

        Long originatingHoldId,

        SeatReservationStatus status,

        LocalDateTime reservedAt,

        LocalDateTime cancelledAt

) {
}
