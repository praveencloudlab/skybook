package com.skybook.praveen.inventoryservice.dto.response;

import com.skybook.praveen.inventoryservice.enums.AircraftSeatStatus;
import com.skybook.praveen.inventoryservice.enums.SeatPosition;
import com.skybook.praveen.inventoryservice.enums.SeatType;

import java.math.BigDecimal;

public record AircraftSeatResponse(

        Long id,

        String seatNumber,

        Integer rowNumber,

        SeatType seatType,

        SeatPosition position,

        AircraftSeatStatus status,

        Boolean exitRow,

        /**
         * LISTED surcharge (SEAT_SELECTION_MODULE.md §3) - what choosing this
         * seat costs. What a passenger is CHARGED is the hold's concern
         * (0.00 when AUTO-assigned), never this field's.
         */
        BigDecimal listedSurcharge

) {
}
