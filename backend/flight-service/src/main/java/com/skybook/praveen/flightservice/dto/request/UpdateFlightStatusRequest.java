package com.skybook.praveen.flightservice.dto.request;

import com.skybook.praveen.flightservice.enums.FlightStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateFlightStatusRequest(

        @NotNull
        FlightStatus status,

        String reason,

        String remarks

) {
}