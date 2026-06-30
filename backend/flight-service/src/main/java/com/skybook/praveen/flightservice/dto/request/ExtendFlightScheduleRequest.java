package com.skybook.praveen.flightservice.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record ExtendFlightScheduleRequest(

        @NotNull(message = "New valid-to date is required")
        @Future(message = "New valid-to date must be in the future")
        LocalDate newValidTo

) {
}
