package com.skybook.praveen.flightservice.service;

import com.skybook.praveen.flightservice.dto.request.CreateFlightRequest;
import com.skybook.praveen.flightservice.dto.request.UpdateFlightRequest;
import com.skybook.praveen.flightservice.dto.response.FlightResponse;
import com.skybook.praveen.flightservice.enums.FlightStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface FlightService {

    // =====================================================
    // Flight CRUD
    // =====================================================

    FlightResponse createFlight(CreateFlightRequest request);

    List<FlightResponse> createFlights(List<CreateFlightRequest> requests);

    FlightResponse getFlightById(Long id);

    List<FlightResponse> getAllFlights();

    FlightResponse updateFlight(Long id, UpdateFlightRequest request);

    void deleteFlight(Long id);

    void restoreFlight(Long id);

    // =====================================================
    // Flight Search
    // =====================================================

    List<FlightResponse> searchFlights(
            String originAirportCode,
            String destinationAirportCode,
            LocalDate departureDate
    );

    List<FlightResponse> getFlightsByStatus(
            FlightStatus status
    );

    List<FlightResponse> getFlightsByDepartureDate(
            LocalDate departureDate
    );

    List<FlightResponse> getFlightsByDepartureDateRange(
            LocalDate startDate,
            LocalDate endDate
    );

    // =====================================================
    // Flight Operations
    // =====================================================

    FlightResponse updateFlightStatus(
            Long id,
            FlightStatus status
    );

    FlightResponse cancelFlight(
            Long id
    );

    FlightResponse delayFlight(
            Long id,
            LocalDateTime newDepartureTime,
            LocalDateTime newArrivalTime
    );

    FlightResponse rescheduleFlight(
            Long id,
            LocalDateTime departureTime,
            LocalDateTime arrivalTime
    );

    FlightResponse boardFlight(
            Long id
    );

    FlightResponse departFlight(
            Long id
    );

    FlightResponse arriveFlight(
            Long id
    );

    // Recurring flight schedule management (FlightSchedule entity,
    // generation, pause/resume/cancel/extend) lives in FlightScheduleService,
    // not here - a schedule is a different lifecycle from a single flight.

    // =====================================================
    // Validation
    // =====================================================

    boolean exists(Long id);

    boolean existsByFlightNumber(String flightNumber);

}
