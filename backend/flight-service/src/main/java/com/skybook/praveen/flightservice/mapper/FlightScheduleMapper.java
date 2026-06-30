package com.skybook.praveen.flightservice.mapper;

import com.skybook.praveen.flightservice.dto.request.CreateFlightScheduleRequest;
import com.skybook.praveen.flightservice.dto.response.FlightScheduleResponse;
import com.skybook.praveen.flightservice.entity.FlightSchedule;
import com.skybook.praveen.flightservice.enums.ScheduleStatus;

public final class FlightScheduleMapper {

    private FlightScheduleMapper() {
    }

    public static FlightSchedule toEntity(CreateFlightScheduleRequest request) {
        return FlightSchedule.builder()
                .flightNumber(request.flightNumber().toUpperCase())
                .airlineCode(request.airlineCode().toUpperCase())
                .originAirportCode(request.originAirportCode().toUpperCase())
                .destinationAirportCode(request.destinationAirportCode().toUpperCase())
                .departureTime(request.departureTime())
                .arrivalTime(request.arrivalTime())
                .operatingDays(request.operatingDays())
                .validFrom(request.validFrom())
                .validTo(request.validTo())
                .status(ScheduleStatus.ACTIVE)
                .build();
    }

    public static FlightScheduleResponse toResponse(FlightSchedule schedule) {
        return new FlightScheduleResponse(
                schedule.getId(),
                schedule.getFlightNumber(),
                schedule.getAirlineCode(),
                schedule.getOriginAirportCode(),
                schedule.getDestinationAirportCode(),
                schedule.getDepartureTime(),
                schedule.getArrivalTime(),
                schedule.getOperatingDays(),
                schedule.getValidFrom(),
                schedule.getValidTo(),
                schedule.getStatus(),
                schedule.getLastGeneratedDate(),
                schedule.getCreatedAt(),
                schedule.getUpdatedAt()
        );
    }
}
