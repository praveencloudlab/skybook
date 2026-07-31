package com.skybook.praveen.bookingservice.dto.request;

import com.skybook.praveen.bookingservice.enums.TravelClass;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Watch a route + date + cabin (fare-watch feature). */
public record FareAlertRequest(

        @NotBlank @Size(min = 3, max = 3)
        String originAirportCode,

        @NotBlank @Size(min = 3, max = 3)
        String destinationAirportCode,

        @NotNull @FutureOrPresent
        LocalDate travelDate,

        @NotNull
        TravelClass travelClass

) {
}
