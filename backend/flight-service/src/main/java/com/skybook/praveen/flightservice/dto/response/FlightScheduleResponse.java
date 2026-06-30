package com.skybook.praveen.flightservice.dto.response;

import com.skybook.praveen.flightservice.enums.ScheduleStatus;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

public record FlightScheduleResponse(
        Long id,
        String flightNumber,
        String airlineCode,
        String originAirportCode,
        String destinationAirportCode,
        LocalTime departureTime,
        LocalTime arrivalTime,
        Set<DayOfWeek> operatingDays,
        LocalDate validFrom,
        LocalDate validTo,
        ScheduleStatus status,
        LocalDate lastGeneratedDate,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
