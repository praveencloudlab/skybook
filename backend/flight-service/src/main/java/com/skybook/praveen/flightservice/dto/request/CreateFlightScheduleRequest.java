package com.skybook.praveen.flightservice.dto.request;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

public record CreateFlightScheduleRequest(

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

        @NotNull(message = "Departure time of day is required")
        LocalTime departureTime,

        @NotNull(message = "Arrival time of day is required")
        LocalTime arrivalTime,

        @NotEmpty(message = "At least one operating day is required")
        Set<DayOfWeek> operatingDays,

        @NotNull(message = "Valid-from date is required")
        @FutureOrPresent(message = "Valid-from date cannot be in the past")
        LocalDate validFrom,

        LocalDate validTo

) {
}
