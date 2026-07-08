package com.skybook.praveen.checkinservice.mapper;

import com.skybook.praveen.checkinservice.dto.response.CheckInResponse;
import com.skybook.praveen.checkinservice.dto.response.FlightManifestResponse;
import com.skybook.praveen.checkinservice.entity.FlightManifest;

import java.math.BigDecimal;
import java.util.List;

public final class FlightManifestMapper {

    private FlightManifestMapper() {
    }

    /**
     * Counts/baggage totals are passed in explicitly rather than read off
     * the entity - ManifestService decides whether they're freshly computed
     * (status OPEN) or the frozen stored values (status FINALIZED, design
     * doc section 3.5/5.7); the mapper stays a pure transform either way.
     */
    public static FlightManifestResponse toResponse(FlightManifest manifest,
                                                      int checkedInCount,
                                                      int boardedCount,
                                                      int noShowCount,
                                                      int baggageCount,
                                                      BigDecimal baggageWeightKg,
                                                      List<CheckInResponse> passengers) {
        return new FlightManifestResponse(
                manifest.getFlightId(),
                manifest.getStatus(),
                manifest.getFinalizedAt(),
                checkedInCount,
                boardedCount,
                noShowCount,
                baggageCount,
                baggageWeightKg,
                passengers
        );
    }
}
