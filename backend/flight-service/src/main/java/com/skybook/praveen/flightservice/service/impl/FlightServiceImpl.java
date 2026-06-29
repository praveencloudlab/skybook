package com.skybook.praveen.flightservice.service.impl;

import com.skybook.praveen.flightservice.dto.request.CreateFlightRequest;
import com.skybook.praveen.flightservice.dto.response.FlightResponse;
import com.skybook.praveen.flightservice.entity.Flight;
import com.skybook.praveen.flightservice.mapper.FlightMapper;
import com.skybook.praveen.flightservice.repository.FlightRepository;
import com.skybook.praveen.flightservice.service.FlightService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FlightServiceImpl implements FlightService {

    private final FlightRepository flightRepository;

    @Override
    public FlightResponse createFlight(CreateFlightRequest request) {
        validateFlightCreation(request);

        Flight flight = FlightMapper.toEntity(request);
        Flight savedFlight = flightRepository.save(flight);

        return FlightMapper.toResponse(savedFlight);
    }

    @Override
    public FlightResponse getFlightById(Long id) {
        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Flight not found with id: " + id));

        return FlightMapper.toResponse(flight);
    }

    @Override
    public List<FlightResponse> searchFlights(
            String originAirportCode,
            String destinationAirportCode,
            LocalDate departureDate) {

        LocalDateTime startOfDay = departureDate.atStartOfDay();
        LocalDateTime endOfDay = departureDate.plusDays(1).atStartOfDay().minusNanos(1);

        return flightRepository
                .findByOriginAirportCodeAndDestinationAirportCodeAndDepartureTimeBetween(
                        originAirportCode.toUpperCase(),
                        destinationAirportCode.toUpperCase(),
                        startOfDay,
                        endOfDay
                )
                .stream()
                .map(FlightMapper::toResponse)
                .toList();
    }

    private void validateFlightCreation(CreateFlightRequest request) {
        if (flightRepository.existsByFlightNumber(request.flightNumber().toUpperCase())) {
            throw new IllegalArgumentException("Flight number already exists: " + request.flightNumber());
        }

        if (!request.arrivalTime().isAfter(request.departureTime())) {
            throw new IllegalArgumentException("Arrival time must be after departure time");
        }

        if (request.originAirportCode().equalsIgnoreCase(request.destinationAirportCode())) {
            throw new IllegalArgumentException("Origin and destination airports must be different");
        }
    }
}