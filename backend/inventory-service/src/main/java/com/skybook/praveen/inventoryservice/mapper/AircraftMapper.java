package com.skybook.praveen.inventoryservice.mapper;

import com.skybook.praveen.inventoryservice.dto.response.AircraftResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatMapResponse;
import com.skybook.praveen.inventoryservice.entity.Aircraft;

public final class AircraftMapper {

    private AircraftMapper() {
    }

    public static AircraftResponse toResponse(Aircraft aircraft) {
        return new AircraftResponse(
                aircraft.getId(),
                aircraft.getRegistrationNumber(),
                aircraft.getManufacturer(),
                aircraft.getModel(),
                aircraft.getTotalSeats(),
                aircraft.getStatus(),
                aircraft.getCreatedBy(),
                aircraft.getUpdatedBy(),
                aircraft.getVersion(),
                aircraft.getCreatedAt(),
                aircraft.getUpdatedAt()
        );
    }

    // Requires the seats collection to be loaded (call inside the service
    // transaction) - seats are already row/seat ordered by the @OrderBy.
    public static SeatMapResponse toSeatMapResponse(Aircraft aircraft) {
        return new SeatMapResponse(
                aircraft.getId(),
                aircraft.getRegistrationNumber(),
                aircraft.getModel(),
                aircraft.getStatus(),
                aircraft.getTotalSeats(),
                aircraft.getSeats().stream().map(AircraftSeatMapper::toResponse).toList()
        );
    }
}
