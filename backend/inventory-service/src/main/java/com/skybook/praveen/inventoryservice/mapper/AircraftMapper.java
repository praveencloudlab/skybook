package com.skybook.praveen.inventoryservice.mapper;

import com.skybook.praveen.inventoryservice.dto.response.AircraftResponse;
import com.skybook.praveen.inventoryservice.dto.response.AircraftSeatResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatMapResponse;
import com.skybook.praveen.inventoryservice.entity.Aircraft;

import java.util.List;

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
    // The seat rows arrive pre-priced: listedSurcharge needs SeatPricingPolicy
    // and each cabin's context, which the service owns.
    public static SeatMapResponse toSeatMapResponse(Aircraft aircraft, List<AircraftSeatResponse> seats) {
        return new SeatMapResponse(
                aircraft.getId(),
                aircraft.getRegistrationNumber(),
                aircraft.getModel(),
                aircraft.getStatus(),
                aircraft.getTotalSeats(),
                seats
        );
    }
}
