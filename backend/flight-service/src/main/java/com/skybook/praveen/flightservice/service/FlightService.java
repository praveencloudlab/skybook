package com.skybook.praveen.flightservice.service;

import com.skybook.praveen.flightservice.dto.request.CreateFlightRequest;
import com.skybook.praveen.flightservice.dto.response.FlightResponse;

import java.time.LocalDate;
import java.util.List;

public interface FlightService {

    FlightResponse createFlight(CreateFlightRequest request);

    FlightResponse getFlightById(Long id);

    List<FlightResponse> searchFlights(
            String originAirportCode,
            String destinationAirportCode,
            LocalDate departureDate);

}