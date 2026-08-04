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
        /**
         * Minutes in the air. Sent rather than left to the caller, because the
         * two times above are wall clocks at DIFFERENT airports and subtracting
         * them measures the flight plus the offset between the zones. Only the
         * server knows the zones, so only the server can answer this.
         */
        long durationMinutes,
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
