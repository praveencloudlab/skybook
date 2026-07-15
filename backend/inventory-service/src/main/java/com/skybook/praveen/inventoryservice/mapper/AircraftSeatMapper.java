package com.skybook.praveen.inventoryservice.mapper;

import com.skybook.praveen.inventoryservice.dto.response.AircraftSeatResponse;
import com.skybook.praveen.inventoryservice.entity.AircraftSeat;

import java.math.BigDecimal;

public final class AircraftSeatMapper {

    private AircraftSeatMapper() {
    }

    // listedSurcharge comes from SeatPricingPolicy - callers price the seat
    // against its cabin's context (§4) because the mapper can't know where
    // the cabin starts.
    public static AircraftSeatResponse toResponse(AircraftSeat seat, BigDecimal listedSurcharge) {
        return new AircraftSeatResponse(
                seat.getId(),
                seat.getSeatNumber(),
                seat.getRowNumber(),
                seat.getSeatType(),
                seat.getPosition(),
                seat.getStatus(),
                seat.getExitRow(),
                listedSurcharge
        );
    }
}
