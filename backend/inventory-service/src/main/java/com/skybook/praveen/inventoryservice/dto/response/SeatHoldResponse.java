package com.skybook.praveen.inventoryservice.dto.response;

import com.skybook.praveen.inventoryservice.enums.SeatAssignmentMode;
import com.skybook.praveen.inventoryservice.enums.SeatHoldStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SeatHoldResponse(

        Long id,

        Long flightId,

        Long aircraftSeatId,

        String seatNumber,

        Long bookingId,

        Long bookingPassengerId,

        // Pricing snapshot (§6). Null on legacy pre-branch holds only.
        SeatAssignmentMode assignmentMode,

        BigDecimal listedSurcharge,

        BigDecimal chargedSurcharge,

        SeatHoldStatus status,

        LocalDateTime heldAt,

        LocalDateTime expiresAt

) {
}
