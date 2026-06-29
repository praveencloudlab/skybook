package com.skybook.praveen.flightservice.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record CreateFlightRequest(

        @NotBlank(message = "Flight number is required")
        @Size(max = 10, message = "Flight number must not exceed 10 characters")
        String flightNumber,

        @NotBlank(message = "Airline code is required")
        @Size(max = 5, message = "Airline code must not exceed 5 characters")
        String airlineCode,

        @NotBlank(message = "Origin airport code is required")
        @Size(min = 3, max = 3, message = "Origin airport code must be exactly 3 characters")
        String originAirportCode,

        @NotBlank(message = "Destination airport code is required")
        @Size(min = 3, max = 3, message = "Destination airport code must be exactly 3 characters")
        String destinationAirportCode,

        @NotNull(message = "Departure time is required")
        @Future(message = "Departure time must be in the future")
        LocalDateTime departureTime,

        @NotNull(message = "Arrival time is required")
        @Future(message = "Arrival time must be in the future")
        LocalDateTime arrivalTime
) {
}