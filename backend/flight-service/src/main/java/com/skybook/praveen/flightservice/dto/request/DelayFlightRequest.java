package com.skybook.praveen.flightservice.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record DelayFlightRequest(

        @NotNull
        @Future
        LocalDateTime newDepartureTime,

        @NotNull
        @Future
        LocalDateTime newArrivalTime,

        @NotBlank
        String reason

) {
}