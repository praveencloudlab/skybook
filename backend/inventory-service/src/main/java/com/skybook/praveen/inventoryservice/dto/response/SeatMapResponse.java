package com.skybook.praveen.inventoryservice.dto.response;

import com.skybook.praveen.inventoryservice.enums.AircraftStatus;

import java.util.List;

/** The full seat map of one aircraft - AircraftSeatResponse rows in row/seat order. */
public record SeatMapResponse(

        Long aircraftId,

        String registrationNumber,

        String model,

        AircraftStatus aircraftStatus,

        Integer totalSeats,

        List<AircraftSeatResponse> seats

) {
}
