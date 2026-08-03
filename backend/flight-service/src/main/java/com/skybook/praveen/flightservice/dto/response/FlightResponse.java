package com.skybook.praveen.flightservice.dto.response;

import com.skybook.praveen.flightservice.enums.FlightStatus;

import java.time.LocalDateTime;

public record FlightResponse(
        Long id,
        String flightNumber,
        String airlineCode,
        String originAirportCode,
        String destinationAirportCode,
        LocalDateTime departureTime,
        LocalDateTime arrivalTime,
        /** The carrier's terminal at each end (TerminalPolicy) - null on pre-terminal rows. */
        String departureTerminal,
        String arrivalTerminal,
        FlightStatus status,
        Long scheduleId,
        String createdBy,
        String updatedBy,
        Long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
