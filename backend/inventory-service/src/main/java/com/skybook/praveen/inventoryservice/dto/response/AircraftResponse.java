package com.skybook.praveen.inventoryservice.dto.response;

import com.skybook.praveen.inventoryservice.enums.AircraftStatus;

import java.time.LocalDateTime;

public record AircraftResponse(

        Long id,

        String registrationNumber,

        String manufacturer,

        String model,

        Integer totalSeats,

        AircraftStatus status,

        String createdBy,

        String updatedBy,

        Long version,

        LocalDateTime createdAt,

        LocalDateTime updatedAt

) {
}
