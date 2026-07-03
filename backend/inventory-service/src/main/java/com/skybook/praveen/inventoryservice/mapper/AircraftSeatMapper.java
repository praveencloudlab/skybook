package com.skybook.praveen.inventoryservice.mapper;

import com.skybook.praveen.inventoryservice.dto.response.AircraftSeatResponse;
import com.skybook.praveen.inventoryservice.entity.AircraftSeat;

public final class AircraftSeatMapper {

    private AircraftSeatMapper() {
    }

    public static AircraftSeatResponse toResponse(AircraftSeat seat) {
        return new AircraftSeatResponse(
                seat.getId(),
                seat.getSeatNumber(),
                seat.getRowNumber(),
                seat.getSeatType(),
                seat.getPosition(),
                seat.getStatus(),
                seat.getExitRow()
        );
    }
}
