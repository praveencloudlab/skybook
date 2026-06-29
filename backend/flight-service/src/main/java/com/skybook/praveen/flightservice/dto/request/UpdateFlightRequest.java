package com.skybook.praveen.flightservice.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record UpdateFlightRequest(

        @NotBlank
        String airlineCode,

        @NotBlank
        String originAirportCode,

        @NotBlank
        String destinationAirportCode,

        @NotNull
        @Future
        LocalDateTime departureTime,

        @NotNull
        @Future
        LocalDateTime arrivalTime

) {
}