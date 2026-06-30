package com.skybook.praveen.flightservice.service;

import com.skybook.praveen.flightservice.dto.request.CreateFlightScheduleRequest;
import com.skybook.praveen.flightservice.dto.response.FlightResponse;
import com.skybook.praveen.flightservice.dto.response.FlightScheduleResponse;

import java.time.LocalDate;
import java.util.List;

public interface FlightScheduleService {

    FlightScheduleResponse createSchedule(CreateFlightScheduleRequest request);

    FlightScheduleResponse getScheduleById(Long id);

    List<FlightScheduleResponse> getAllSchedules();

    FlightScheduleResponse pauseSchedule(Long id, String reason, String remarks);

    FlightScheduleResponse resumeSchedule(Long id);

    /** Cancels the schedule and all of its not-yet-departed generated flights. */
    FlightScheduleResponse cancelSchedule(Long id, String reason, String remarks);

    FlightScheduleResponse extendSchedule(Long id, LocalDate newValidTo);

    /**
     * Generates concrete Flight instances for the given schedule, covering the
     * next N days from wherever generation last left off. If
     * {@code horizonDaysOverride} is null, the schedule's own
     * {@code generationDaysAhead} is used. Idempotent - safe to call
     * repeatedly, never creates duplicate flights.
     */
    List<FlightResponse> generateFlights(Long scheduleId, Integer horizonDaysOverride);

    /** Runs generateFlights for every ACTIVE schedule, each using its own generationDaysAhead. Used by the scheduled job. */
    void generateFlightsForAllActiveSchedules();
}
