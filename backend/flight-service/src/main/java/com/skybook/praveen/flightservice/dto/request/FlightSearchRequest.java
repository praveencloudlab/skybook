package com.skybook.praveen.flightservice.dto.request;

import com.skybook.praveen.flightservice.enums.FlightStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public record FlightSearchRequest(

        String originAirportCode,

        String destinationAirportCode,

        String airlineCode,

        LocalDate departureDate,

        LocalDate returnDate,

        FlightStatus status,

        BigDecimal minimumPrice,

        BigDecimal maximumPrice,

        Integer passengers

) {
}