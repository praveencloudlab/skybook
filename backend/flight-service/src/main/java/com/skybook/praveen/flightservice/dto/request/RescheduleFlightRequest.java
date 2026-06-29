package com.skybook.praveen.flightservice.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record RescheduleFlightRequest(

        @NotNull
        @Future
        LocalDateTime departureTime,

        @NotNull
        @Future
        LocalDateTime arrivalTime,

        String remarks

) {
}