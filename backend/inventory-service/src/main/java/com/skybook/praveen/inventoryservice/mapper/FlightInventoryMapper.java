package com.skybook.praveen.inventoryservice.mapper;

import com.skybook.praveen.inventoryservice.dto.response.FlightInventoryResponse;
import com.skybook.praveen.inventoryservice.entity.FlightInventory;

public final class FlightInventoryMapper {

    private FlightInventoryMapper() {
    }

    public static FlightInventoryResponse toResponse(FlightInventory inventory) {
        return new FlightInventoryResponse(
                inventory.getId(),
                inventory.getFlightId(),
                inventory.getAircraft().getId(),
                inventory.getAircraft().getRegistrationNumber(),
                inventory.getStatus(),
                inventory.getTotalSeats(),
                inventory.getAvailableSeats(),
                inventory.getHeldSeats(),
                inventory.getReservedSeats(),
                inventory.getBlockedSeats(),
                inventory.getVersion(),
                inventory.getCreatedAt(),
                inventory.getUpdatedAt()
        );
    }
}
