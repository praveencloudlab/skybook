package com.skybook.praveen.checkinservice.dto.response;

import com.skybook.praveen.checkinservice.enums.ManifestStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * counts/baggage* are computed live by ManifestService while status is
 * OPEN and read from the frozen row once FINALIZED (design doc section 3.5).
 */
public record FlightManifestResponse(

        Long flightId,

        ManifestStatus status,

        LocalDateTime finalizedAt,

        int checkedInCount,

        int boardedCount,

        int noShowCount,

        int baggageCount,

        BigDecimal baggageWeightKg,

        List<CheckInResponse> passengers

) {
}
