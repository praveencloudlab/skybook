package com.skybook.praveen.checkinservice.mapper;

import com.skybook.praveen.checkinservice.dto.response.BaggageResponse;
import com.skybook.praveen.checkinservice.entity.Baggage;

public final class BaggageMapper {

    private BaggageMapper() {
    }

    public static BaggageResponse toResponse(Baggage baggage) {
        return new BaggageResponse(
                baggage.getId(),
                baggage.getCheckIn().getId(),
                baggage.getTagNumber(),
                baggage.getWeightKg(),
                baggage.isExcess(),
                baggage.getExcessCharge()
        );
    }
}
