package com.skybook.praveen.checkinservice.dto.response;

import java.math.BigDecimal;

public record BaggageResponse(

        Long id,

        Long checkInId,

        String tagNumber,

        BigDecimal weightKg,

        boolean excess,

        BigDecimal excessCharge

) {
}
