package com.skybook.praveen.inventoryservice.dto.response;

import com.skybook.praveen.inventoryservice.enums.SeatHoldStatus;

import java.time.LocalDateTime;

public record SeatHoldResponse(

        Long id,

        Long flightId,

        Long aircraftSeatId,

        String seatNumber,

        Long bookingId,

        SeatHoldStatus status,

        LocalDateTime heldAt,

        LocalDateTime expiresAt

) {
}
