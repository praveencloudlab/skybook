package com.skybook.praveen.flightservice.service.impl;

import com.skybook.praveen.flightservice.dto.request.CreateFlightRequest;
import com.skybook.praveen.flightservice.dto.request.UpdateFlightRequest;
import com.skybook.praveen.flightservice.dto.response.FlightResponse;
import com.skybook.praveen.flightservice.entity.Flight;
import com.skybook.praveen.flightservice.enums.FlightStatus;
import com.skybook.praveen.flightservice.exception.FlightNotFoundException;
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
    public List<FlightResponse> createFlights(List<CreateFlightRequest> requests) {

        requests.forEach(this::validateFlightCreation);

        List<Flight> flights = requests.stream()
                .map(FlightMapper::toEntity)
                .toList();

        List<Flight> savedFlights = flightRepository.saveAll(flights);

        return savedFlights.stream()
                .map(FlightMapper::toResponse)
                .toList();
    }

    @Override
    public FlightResponse getFlightById(Long id) {

        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new FlightNotFoundException(id));

        return FlightMapper.toResponse(flight);
    }

    @Override
    public List<FlightResponse> getAllFlights() {

        return flightRepository.findAll()
                .stream()
                .map(FlightMapper::toResponse)
                .toList();
    }

    @Override
    public FlightResponse updateFlight(Long id,
                                       UpdateFlightRequest request) {

        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new FlightNotFoundException(id));

        if (!request.arrivalTime().isAfter(request.departureTime())) {
            throw new IllegalArgumentException(
                    "Arrival time must be after departure time");
        }

        if (request.originAirportCode()
                .equalsIgnoreCase(request.destinationAirportCode())) {

            throw new IllegalArgumentException(
                    "Origin and destination airports must be different");
        }

        FlightMapper.updateEntity(flight, request);

        Flight updatedFlight = flightRepository.save(flight);

        return FlightMapper.toResponse(updatedFlight);
    }

    @Override
    public void deleteFlight(Long id) {

        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new FlightNotFoundException(id));

        flightRepository.delete(flight);
    }

    @Override
    public void restoreFlight(Long id) {

        throw new UnsupportedOperationException(
                "Restore Flight will be implemented with Soft Delete.");
    }

    private void validateFlightCreation(CreateFlightRequest request) {

        // Flight numbers are not globally unique - the same number can recur
        // daily under a schedule. Uniqueness is per (flightNumber, departureTime).
        if (flightRepository.existsByFlightNumberAndDepartureTime(
                request.flightNumber().toUpperCase(),
                request.departureTime())) {

            throw new IllegalArgumentException(
                    "Flight " + request.flightNumber()
                            + " already exists for departure time " + request.departureTime());
        }

        if (!request.arrivalTime().isAfter(request.departureTime())) {

            throw new IllegalArgumentException(
                    "Arrival time must be after departure time");
        }

        if (request.originAirportCode()
                .equalsIgnoreCase(request.destinationAirportCode())) {

            throw new IllegalArgumentException(
                    "Origin and destination airports must be different");
        }
    }

    @Override
    public List<FlightResponse> searchFlights(
            String originAirportCode,
            String destinationAirportCode,
            LocalDate departureDate) {

        LocalDateTime start = departureDate.atStartOfDay();
        LocalDateTime end = departureDate.plusDays(1)
                .atStartOfDay()
                .minusNanos(1);

        return flightRepository
                .findByOriginAirportCodeAndDestinationAirportCodeAndDepartureTimeBetween(
                        originAirportCode.toUpperCase(),
                        destinationAirportCode.toUpperCase(),
                        start,
                        end)
                .stream()
                .map(FlightMapper::toResponse)
                .toList();
    }

    @Override
    public List<FlightResponse> getFlightsByStatus(FlightStatus status) {

        return flightRepository.findByStatus(status)
                .stream()
                .map(FlightMapper::toResponse)
                .toList();
    }

    @Override
    public List<FlightResponse> getFlightsByDepartureDate(
            LocalDate departureDate) {

        LocalDateTime start = departureDate.atStartOfDay();
        LocalDateTime end = departureDate.plusDays(1)
                .atStartOfDay()
                .minusNanos(1);

        return flightRepository.findByDepartureTimeBetween(start, end)
                .stream()
                .map(FlightMapper::toResponse)
                .toList();
    }

    @Override
    public List<FlightResponse> getFlightsByDepartureDateRange(
            LocalDate startDate,
            LocalDate endDate) {

        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1)
                .atStartOfDay()
                .minusNanos(1);

        return flightRepository.findByDepartureTimeBetween(start, end)
                .stream()
                .map(FlightMapper::toResponse)
                .toList();
    }

    @Override
    public FlightResponse updateFlightStatus(
            Long id,
            FlightStatus status) {

        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new FlightNotFoundException(id));

        flight.setStatus(status);

        return FlightMapper.toResponse(
                flightRepository.save(flight));
    }

    @Override
    public FlightResponse cancelFlight(Long id) {

        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new FlightNotFoundException(id));

        flight.setStatus(FlightStatus.CANCELLED);

        return FlightMapper.toResponse(
                flightRepository.save(flight));
    }

    @Override
    public FlightResponse delayFlight(
            Long id,
            LocalDateTime newDepartureTime,
            LocalDateTime newArrivalTime) {

        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new FlightNotFoundException(id));

        if (!newArrivalTime.isAfter(newDepartureTime)) {
            throw new IllegalArgumentException(
                    "Arrival time must be after departure time");
        }

        flight.setDepartureTime(newDepartureTime);
        flight.setArrivalTime(newArrivalTime);
        flight.setStatus(FlightStatus.DELAYED);

        return FlightMapper.toResponse(
                flightRepository.save(flight));
    }

    @Override
    public FlightResponse rescheduleFlight(
            Long id,
            LocalDateTime departureTime,
            LocalDateTime arrivalTime) {

        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new FlightNotFoundException(id));

        if (!arrivalTime.isAfter(departureTime)) {
            throw new IllegalArgumentException(
                    "Arrival time must be after departure time");
        }

        flight.setDepartureTime(departureTime);
        flight.setArrivalTime(arrivalTime);

        return FlightMapper.toResponse(
                flightRepository.save(flight));
    }

    @Override
    public FlightResponse boardFlight(Long id) {

        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new FlightNotFoundException(id));

        flight.setStatus(FlightStatus.BOARDING);

        return FlightMapper.toResponse(
                flightRepository.save(flight));
    }

    @Override
    public FlightResponse departFlight(Long id) {

        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new FlightNotFoundException(id));

        flight.setStatus(FlightStatus.DEPARTED);

        return FlightMapper.toResponse(
                flightRepository.save(flight));
    }

    @Override
    public FlightResponse arriveFlight(Long id) {

        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new FlightNotFoundException(id));

        flight.setStatus(FlightStatus.ARRIVED);

        return FlightMapper.toResponse(
                flightRepository.save(flight));
    }

    @Override
    public boolean exists(Long id) {
        return flightRepository.existsById(id);
    }

    @Override
    public boolean existsByFlightNumber(String flightNumber) {
        return flightRepository.existsByFlightNumber(
                flightNumber.toUpperCase());
    }
}
