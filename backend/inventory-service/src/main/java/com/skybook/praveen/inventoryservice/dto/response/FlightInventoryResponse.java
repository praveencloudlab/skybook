package com.skybook.praveen.inventoryservice.dto.response;

import com.skybook.praveen.inventoryservice.enums.InventoryStatus;

import java.time.LocalDateTime;

public record FlightInventoryResponse(

        Long id,

        Long flightId,

        Long aircraftId,

        String aircraftRegistrationNumber,

        InventoryStatus status,

        Integer totalSeats,

        Integer availableSeats,

        Integer heldSeats,

        Integer reservedSeats,

        Integer blockedSeats,

        Long version,

        LocalDateTime createdAt,

        LocalDateTime updatedAt

) {
}
