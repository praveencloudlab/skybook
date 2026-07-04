package com.skybook.praveen.inventoryservice.service;

import com.skybook.praveen.inventoryservice.dto.request.CreateAircraftSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.CreateSeatMapRequest;
import com.skybook.praveen.inventoryservice.dto.response.AircraftSeatResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatMapResponse;
import com.skybook.praveen.inventoryservice.enums.AircraftSeatStatus;

import java.util.List;

/** Seat-map operations for one aircraft - seats are always reached through their Aircraft. */
public interface AircraftSeatService {

    AircraftSeatResponse addSeat(Long aircraftId, CreateAircraftSeatRequest request);

    /** Bulk creation - all-or-nothing (single transaction). */
    List<AircraftSeatResponse> createSeatMap(Long aircraftId, CreateSeatMapRequest request);

    SeatMapResponse getSeatMap(Long aircraftId);

    List<AircraftSeatResponse> getSeatsByStatus(Long aircraftId, AircraftSeatStatus status);

    /** BLOCKED <-> ACTIVE <-> INOPERATIVE - no terminal states at seat level. */
    AircraftSeatResponse updateSeatStatus(Long aircraftId, String seatNumber, AircraftSeatStatus newStatus);
}
