package com.skybook.praveen.inventoryservice.mapper;

import com.skybook.praveen.inventoryservice.dto.response.SeatHoldResponse;
import com.skybook.praveen.inventoryservice.entity.SeatHold;

public final class SeatHoldMapper {

    private SeatHoldMapper() {
    }

    public static SeatHoldResponse toResponse(SeatHold hold) {
        return new SeatHoldResponse(
                hold.getId(),
                hold.getFlightInventory().getFlightId(),
                hold.getAircraftSeat().getId(),
                hold.getAircraftSeat().getSeatNumber(),
                hold.getBookingId(),
                hold.getStatus(),
                hold.getHeldAt(),
                hold.getExpiresAt()
        );
    }
}
