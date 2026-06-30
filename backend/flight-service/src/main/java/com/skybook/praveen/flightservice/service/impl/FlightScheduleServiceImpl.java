package com.skybook.praveen.flightservice.service.impl;

import com.skybook.praveen.flightservice.dto.request.CreateFlightScheduleRequest;
import com.skybook.praveen.flightservice.dto.response.FlightResponse;
import com.skybook.praveen.flightservice.dto.response.FlightScheduleResponse;
import com.skybook.praveen.flightservice.entity.Flight;
import com.skybook.praveen.flightservice.entity.FlightSchedule;
import com.skybook.praveen.flightservice.enums.FlightStatus;
import com.skybook.praveen.flightservice.enums.ScheduleStatus;
import com.skybook.praveen.flightservice.exception.FlightScheduleNotFoundException;
import com.skybook.praveen.flightservice.mapper.FlightMapper;
import com.skybook.praveen.flightservice.mapper.FlightScheduleMapper;
import com.skybook.praveen.flightservice.repository.FlightRepository;
import com.skybook.praveen.flightservice.repository.FlightScheduleRepository;
import com.skybook.praveen.flightservice.service.FlightScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlightScheduleServiceImpl implements FlightScheduleService {

    private final FlightScheduleRepository flightScheduleRepository;
    private final FlightRepository flightRepository;

    @Override
    public FlightScheduleResponse createSchedule(CreateFlightScheduleRequest request) {

        if (request.originAirportCode().equalsIgnoreCase(request.destinationAirportCode())) {
            throw new IllegalArgumentException("Origin and destination airports must be different");
        }

        if (request.validTo() != null && !request.validTo().isAfter(request.validFrom())) {
            throw new IllegalArgumentException("validTo must be after validFrom");
        }

        FlightSchedule schedule = FlightScheduleMapper.toEntity(request);

        FlightSchedule saved = flightScheduleRepository.save(schedule);

        return FlightScheduleMapper.toResponse(saved);
    }

    @Override
    public FlightScheduleResponse getScheduleById(Long id) {
        return FlightScheduleMapper.toResponse(findScheduleOrThrow(id));
    }

    @Override
    public List<FlightScheduleResponse> getAllSchedules() {
        return flightScheduleRepository.findAll()
                .stream()
                .map(FlightScheduleMapper::toResponse)
                .toList();
    }

    @Override
    public FlightScheduleResponse pauseSchedule(Long id) {

        FlightSchedule schedule = findScheduleOrThrow(id);

        if (schedule.getStatus() == ScheduleStatus.CANCELLED) {
            throw new IllegalStateException("Cancelled schedules cannot be paused");
        }

        schedule.setStatus(ScheduleStatus.PAUSED);

        return FlightScheduleMapper.toResponse(flightScheduleRepository.save(schedule));
    }

    @Override
    public FlightScheduleResponse resumeSchedule(Long id) {

        FlightSchedule schedule = findScheduleOrThrow(id);

        if (schedule.getStatus() != ScheduleStatus.PAUSED) {
            throw new IllegalStateException("Only paused schedules can be resumed");
        }

        schedule.setStatus(ScheduleStatus.ACTIVE);

        return FlightScheduleMapper.toResponse(flightScheduleRepository.save(schedule));
    }

    @Override
    @Transactional
    public FlightScheduleResponse cancelSchedule(Long id) {

        FlightSchedule schedule = findScheduleOrThrow(id);

        schedule.setStatus(ScheduleStatus.CANCELLED);
        flightScheduleRepository.save(schedule);

        List<Flight> futureGeneratedFlights =
                flightRepository.findByScheduleIdAndDepartureTimeAfter(id, LocalDateTime.now());

        futureGeneratedFlights.forEach(flight -> {
            if (flight.getStatus() == FlightStatus.SCHEDULED || flight.getStatus() == FlightStatus.DELAYED) {
                flight.setStatus(FlightStatus.CANCELLED);
            }
        });

        flightRepository.saveAll(futureGeneratedFlights);

        return FlightScheduleMapper.toResponse(schedule);
    }

    @Override
    public FlightScheduleResponse extendSchedule(Long id, LocalDate newValidTo) {

        FlightSchedule schedule = findScheduleOrThrow(id);

        if (schedule.getStatus() == ScheduleStatus.CANCELLED) {
            throw new IllegalStateException("Cancelled schedules cannot be extended");
        }

        if (schedule.getValidTo() != null && !newValidTo.isAfter(schedule.getValidTo())) {
            throw new IllegalArgumentException("New validTo must be after the current validTo");
        }

        schedule.setValidTo(newValidTo);

        // A schedule that already ran to completion can be revived by extending it.
        if (schedule.getStatus() == ScheduleStatus.COMPLETED) {
            schedule.setStatus(ScheduleStatus.ACTIVE);
        }

        return FlightScheduleMapper.toResponse(flightScheduleRepository.save(schedule));
    }

    @Override
    @Transactional
    public List<FlightResponse> generateFlights(Long scheduleId, int horizonDays) {

        FlightSchedule schedule = findScheduleOrThrow(scheduleId);

        if (schedule.getStatus() != ScheduleStatus.ACTIVE) {
            log.info("Skipping generation for schedule {} - status is {}", scheduleId, schedule.getStatus());
            return List.of();
        }

        LocalDate today = LocalDate.now();

        LocalDate windowStart = schedule.getLastGeneratedDate() != null
                ? schedule.getLastGeneratedDate().plusDays(1)
                : schedule.getValidFrom();

        if (windowStart.isBefore(today)) {
            windowStart = today;
        }

        LocalDate windowEnd = windowStart.plusDays(horizonDays);

        if (schedule.getValidTo() != null && windowEnd.isAfter(schedule.getValidTo())) {
            windowEnd = schedule.getValidTo();
        }

        if (windowEnd.isBefore(windowStart)) {
            // Schedule has fully expired - nothing left to generate.
            schedule.setStatus(ScheduleStatus.COMPLETED);
            flightScheduleRepository.save(schedule);
            return List.of();
        }

        List<Flight> generated = new ArrayList<>();
        LocalDate cursor = windowStart;

        while (!cursor.isAfter(windowEnd)) {

            if (schedule.getOperatingDays().contains(cursor.getDayOfWeek())) {

                LocalDateTime departureDateTime = cursor.atTime(schedule.getDepartureTime());

                LocalDateTime arrivalDateTime = schedule.getArrivalTime().isBefore(schedule.getDepartureTime())
                        ? cursor.plusDays(1).atTime(schedule.getArrivalTime())
                        : cursor.atTime(schedule.getArrivalTime());

                boolean alreadyExists = flightRepository.existsByFlightNumberAndDepartureTime(
                        schedule.getFlightNumber(), departureDateTime);

                if (!alreadyExists) {

                    Flight flight = Flight.builder()
                            .flightNumber(schedule.getFlightNumber())
                            .airlineCode(schedule.getAirlineCode())
                            .originAirportCode(schedule.getOriginAirportCode())
                            .destinationAirportCode(schedule.getDestinationAirportCode())
                            .departureTime(departureDateTime)
                            .arrivalTime(arrivalDateTime)
                            .status(FlightStatus.SCHEDULED)
                            .scheduleId(schedule.getId())
                            .build();

                    generated.add(flight);
                }
            }

            cursor = cursor.plusDays(1);
        }

        if (!generated.isEmpty()) {
            flightRepository.saveAll(generated);
        }

        schedule.setLastGeneratedDate(windowEnd);

        if (schedule.getValidTo() != null && !windowEnd.isBefore(schedule.getValidTo())) {
            schedule.setStatus(ScheduleStatus.COMPLETED);
        }

        flightScheduleRepository.save(schedule);

        log.info("Generated {} flight instance(s) for schedule {}", generated.size(), scheduleId);

        return generated.stream().map(FlightMapper::toResponse).toList();
    }

    @Override
    public void generateFlightsForAllActiveSchedules(int horizonDays) {

        List<FlightSchedule> activeSchedules =
                flightScheduleRepository.findByStatus(ScheduleStatus.ACTIVE);

        activeSchedules.forEach(schedule -> generateFlights(schedule.getId(), horizonDays));
    }

    private FlightSchedule findScheduleOrThrow(Long id) {
        return flightScheduleRepository.findById(id)
                .orElseThrow(() -> new FlightScheduleNotFoundException(id));
    }
}
