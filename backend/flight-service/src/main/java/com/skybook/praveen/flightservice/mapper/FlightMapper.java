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
                flight.getScheduleId(),
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
