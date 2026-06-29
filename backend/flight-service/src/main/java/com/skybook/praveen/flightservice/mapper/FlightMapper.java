package com.skybook.praveen.flightservice.mapper;

import com.skybook.praveen.flightservice.dto.request.CreateFlightRequest;
import com.skybook.praveen.flightservice.dto.response.FlightResponse;
import com.skybook.praveen.flightservice.entity.Flight;
import com.skybook.praveen.flightservice.enums.FlightStatus;

public final class FlightMapper {

    private FlightMapper() {
    }

    public static Flight toEntity(CreateFlightRequest request) {
        return Flight.builder()
                .flightNumber(request.flightNumber().toUpperCase())
                .airlineCode(request.airlineCode().toUpperCase())
                .originAirportCode(request.originAirportCode().toUpperCase())
                .destinationAirportCode(request.destinationAirportCode().toUpperCase())
                .departureTime(request.departureTime())
                .arrivalTime(request.arrivalTime())
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
                flight.getStatus(),
                flight.getCreatedAt(),
                flight.getUpdatedAt()
        );
    }
}