package com.skybook.praveen.inventoryservice.service;

import com.skybook.praveen.inventoryservice.dto.request.CreateAircraftRequest;
import com.skybook.praveen.inventoryservice.dto.response.AircraftResponse;
import com.skybook.praveen.inventoryservice.enums.AircraftStatus;

import java.util.List;

/**
 * Owns the Aircraft aggregate root (airframe master data). Seat-map
 * operations live in AircraftSeatService; per-flight sellability in
 * InventoryService.
 */
public interface AircraftService {

    AircraftResponse createAircraft(CreateAircraftRequest request);

    AircraftResponse getAircraftById(Long id);

    AircraftResponse getAircraftByRegistrationNumber(String registrationNumber);

    List<AircraftResponse> getAllAircraft();

    List<AircraftResponse> getAircraftByStatus(AircraftStatus status);

    /** RETIRED is terminal - any transition out of it is rejected. */
    AircraftResponse updateAircraftStatus(Long id, AircraftStatus newStatus);
}
