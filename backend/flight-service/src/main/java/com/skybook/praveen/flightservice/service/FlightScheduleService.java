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

    FlightScheduleResponse pauseSchedule(Long id);

    FlightScheduleResponse resumeSchedule(Long id);

    /** Cancels the schedule and all of its not-yet-departed generated flights. */
    FlightScheduleResponse cancelSchedule(Long id);

    FlightScheduleResponse extendSchedule(Long id, LocalDate newValidTo);

    /**
     * Generates concrete Flight instances for the given schedule, covering the
     * next {@code horizonDays} days from wherever generation last left off.
     * Idempotent - safe to call repeatedly, never creates duplicate flights.
     */
    List<FlightResponse> generateFlights(Long scheduleId, int horizonDays);

    /** Runs generateFlights for every ACTIVE schedule. Used by the scheduled job. */
    void generateFlightsForAllActiveSchedules(int horizonDays);
}
