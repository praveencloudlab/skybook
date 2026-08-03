package com.skybook.praveen.flightservice.mapper;

import com.skybook.praveen.flightservice.dto.request.CreateFlightRequest;
import com.skybook.praveen.flightservice.dto.request.UpdateFlightRequest;
import com.skybook.praveen.flightservice.dto.response.FlightResponse;
import com.skybook.praveen.flightservice.entity.Flight;
import com.skybook.praveen.flightservice.enums.FlightStatus;

public final class FlightMapper {

    private FlightMapper() {
    }

    public static Flight toEntity(CreateFlightRequest request) {
        String airline = request.airlineCode().toUpperCase();
        String origin = request.originAirportCode().toUpperCase();
        String destination = request.destinationAirportCode().toUpperCase();
        return Flight.builder()
                .flightNumber(request.flightNumber().toUpperCase())
                .airlineCode(airline)
                .originAirportCode(origin)
                .destinationAirportCode(destination)
                .departureTime(request.departureTime())
                .arrivalTime(request.arrivalTime())
                // Every scheduled flight gets its carrier's REAL terminals
                // (TerminalPolicy) - the e-ticket prints these, so they must
                // never be invented downstream.
                .departureTerminal(com.skybook.praveen.flightservice.domain.TerminalPolicy.terminalFor(airline, origin))
                .arrivalTerminal(com.skybook.praveen.flightservice.domain.TerminalPolicy.terminalFor(airline, destination))
                .status(FlightStatus.SCHEDULED)
                .build();
    }

    public static FlightResponse toResponse(Flight flight) {
        return new FlightResponse(
                flight.getId(),
                flight.getFlightNumber(),
                flight.getAirlineCode(),
                flight.getOriginAirportCode(),
                flight.getDestinationAirportCode(),
                flight.getDepartureTime(),
                flight.getArrivalTime(),
                flight.getDepartureTerminal(),
                flight.getArrivalTerminal(),
                flight.getStatus(),
                flight.getSchedule() != null ? flight.getSchedule().getId() : null,
                flight.getCreatedBy(),
                flight.getUpdatedBy(),
                flight.getVersion(),
                flight.getCreatedAt(),
                flight.getUpdatedAt()
        );
    }

    public static void updateEntity(
            Flight flight,
            UpdateFlightRequest request) {

        flight.setAirlineCode(request.airlineCode().toUpperCase());
        flight.setOriginAirportCode(request.originAirportCode().toUpperCase());
        flight.setDestinationAirportCode(request.destinationAirportCode().toUpperCase());
        flight.setDepartureTime(request.departureTime());
        flight.setArrivalTime(request.arrivalTime());
    }
}
