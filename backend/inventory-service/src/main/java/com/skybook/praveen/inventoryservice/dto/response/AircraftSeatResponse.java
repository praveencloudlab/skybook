package com.skybook.praveen.inventoryservice.dto.response;

import com.skybook.praveen.inventoryservice.enums.AircraftSeatStatus;
import com.skybook.praveen.inventoryservice.enums.SeatPosition;
import com.skybook.praveen.inventoryservice.enums.SeatType;

public record AircraftSeatResponse(

        Long id,

        String seatNumber,

        Integer rowNumber,

        SeatType seatType,

        SeatPosition position,

        AircraftSeatStatus status,

        Boolean exitRow

) {
}
