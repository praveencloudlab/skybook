package com.skybook.praveen.flightservice.controller;

import com.skybook.praveen.flightservice.dto.request.CancelFlightScheduleRequest;
import com.skybook.praveen.flightservice.dto.request.CreateFlightScheduleRequest;
import com.skybook.praveen.flightservice.dto.request.ExtendFlightScheduleRequest;
import com.skybook.praveen.flightservice.dto.request.PauseFlightScheduleRequest;
import com.skybook.praveen.flightservice.dto.response.FlightResponse;
import com.skybook.praveen.flightservice.dto.response.FlightScheduleResponse;
import com.skybook.praveen.flightservice.service.FlightScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/flight-schedules")
@RequiredArgsConstructor
@Tag(
        name = "Flight Schedule Management",
        description = "Recurring flight schedule and automatic flight generation APIs"
)
public class FlightScheduleController {

    private final FlightScheduleService flightScheduleService;

    @Operation(
            summary = "Create Flight Schedule",
            description = "Creates a new recurring flight schedule template. " +
                    "Assigns an immutable, system-generated scheduleCode."
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FlightScheduleResponse createSchedule(
            @Valid @RequestBody CreateFlightScheduleRequest request) {
        return flightScheduleService.createSchedule(request);
    }

    @Operation(summary = "Get Flight Schedule By Id")
    @GetMapping("/{id}")
    public FlightScheduleResponse getScheduleById(@PathVariable Long id) {
        return flightScheduleService.getScheduleById(id);
    }

    @Operation(summary = "Get All Flight Schedules")
    @GetMapping
    public List<FlightScheduleResponse> getAllSchedules() {
        return flightScheduleService.getAllSchedules();
    }

    @Operation(
            summary = "Pause Flight Schedule",
            description = "Stops future automatic flight generation. Already-generated flights are untouched. " +
                    "Optionally records why, e.g. Runway Maintenance / Weather / Political Restrictions."
    )
    @PatchMapping("/{id}/pause")
    public FlightScheduleResponse pauseSchedule(
            @PathVariable Long id,
            @RequestBody(required = false) PauseFlightScheduleRequest request) {
        PauseFlightScheduleRequest body = request != null ? request : new PauseFlightScheduleRequest(null, null);
        return flightScheduleService.pauseSchedule(id, body.reason(), body.remarks());
    }

    @Operation(
            summary = "Resume Flight Schedule",
            description = "Resumes generation for a paused schedule and clears any pause reason/remarks."
    )
    @PatchMapping("/{id}/resume")
    public FlightScheduleResponse resumeSchedule(@PathVariable Long id) {
        return flightScheduleService.resumeSchedule(id);
    }

    @Operation(
            summary = "Cancel Flight Schedule",
            description = "Cancels the schedule and all of its not-yet-departed generated flights. " +
                    "Optionally records why."
    )
    @PatchMapping("/{id}/cancel")
    public FlightScheduleResponse cancelSchedule(
            @PathVariable Long id,
            @RequestBody(required = false) CancelFlightScheduleRequest request) {
        CancelFlightScheduleRequest body = request != null ? request : new CancelFlightScheduleRequest(null, null);
        return flightScheduleService.cancelSchedule(id, body.reason(), body.remarks());
    }

    @Operation(
            summary = "Extend Flight Schedule",
            description = "Pushes the schedule's validTo date further into the future."
    )
    @PatchMapping("/{id}/extend")
    public FlightScheduleResponse extendSchedule(
            @PathVariable Long id,
            @Valid @RequestBody ExtendFlightScheduleRequest request) {
        return flightScheduleService.extendSchedule(id, request.newValidTo());
    }

    @Operation(
            summary = "Generate Flights",
            description = "Manually triggers flight instance generation for this schedule, covering the given " +
                    "number of days from wherever generation last left off. If horizonDays is omitted, the " +
                    "schedule's own generationDaysAhead is used."
    )
    @PostMapping("/{id}/generate")
    public List<FlightResponse> generateFlights(
            @PathVariable Long id,
            @RequestParam(required = false) Integer horizonDays) {
        return flightScheduleService.generateFlights(id, horizonDays);
    }
}
