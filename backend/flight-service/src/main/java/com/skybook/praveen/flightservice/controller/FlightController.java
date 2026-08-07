package com.skybook.praveen.flightservice.controller;

import com.skybook.praveen.flightservice.dto.request.*;
import com.skybook.praveen.flightservice.dto.response.FlightResponse;
import com.skybook.praveen.flightservice.enums.FlightStatus;
import com.skybook.praveen.flightservice.service.FlightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;


@RestController
@RequestMapping("/api/flights")
@RequiredArgsConstructor
@Tag(
        name = "Flight Management",
        description = "Flight Management APIs for SkyBook Airline Reservation System"
)
public class FlightController {

    private final FlightService flightService;

    // =====================================================
    // CREATE
    // =====================================================

    @Operation(
            summary = "Create Flight",
            description = "Creates a new flight."
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FlightResponse createFlight(
            @Valid @RequestBody CreateFlightRequest request) {
        return flightService.createFlight(request);
    }

    @Operation(
            summary = "Bulk Create Flights",
            description = "Creates multiple flights in a single request."
    )
    @PostMapping("/bulk")
    @ResponseStatus(HttpStatus.CREATED)
    public List<FlightResponse> createFlights(
            @Valid @RequestBody List<CreateFlightRequest> requests) {
        return flightService.createFlights(requests);
    }

    // =====================================================
    // READ
    // =====================================================

    @Operation(
            summary = "Get Flight By Id",
            description = "Returns a flight by its unique identifier."
    )
    @GetMapping("/{id}")
    public FlightResponse getFlightById(
            @PathVariable Long id) {
        return flightService.getFlightById(id);
    }

    @Operation(
            summary = "Get All Flights",
            description = "Returns all flights."
    )
    /**
     * List flights, a PAGE at a time.
     *
     * <p>This used to be an unbounded {@code findAll()}. The seed network is
     * ~920,000 rows; mapping every one into a DTO exhausted the heap, and the
     * failure surfaced at the edge as a misleading 401 (the error re-dispatch
     * to {@code /error} is not a public path). Nothing in the app consumed the
     * full list - shopping goes through {@code /search} and {@code /itineraries}
     * - so the endpoint is now paged, capped, and honest about its size.
     */
    @GetMapping
    public org.springframework.data.domain.Page<FlightResponse> getAllFlights(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        // Hard ceiling: a caller asking for a million rows a page is the very
        // request this endpoint exists to refuse.
        int capped = Math.min(Math.max(size, 1), 200);
        return flightService.getAllFlights(
                org.springframework.data.domain.PageRequest.of(Math.max(page, 0), capped));
    }

    @Operation(
            summary = "Trip itineraries",
            description = "Direct flights plus 1-stop and 2-stop connections for a route and date, "
                    + "with per-stop layover times. Public shopping data, like /search."
    )
    @GetMapping("/itineraries")
    public List<com.skybook.praveen.flightservice.dto.response.ItineraryResponse> getItineraries(
            @RequestParam String originAirportCode,
            @RequestParam String destinationAirportCode,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate departureDate) {
        return flightService.getItineraries(originAirportCode, destinationAirportCode, departureDate);
    }

    @Operation(
            summary = "Search Flights",
            description = "Search flights by origin, destination and departure date."
    )
    @GetMapping("/search")
    public List<FlightResponse> searchFlights(
            @RequestParam String originAirportCode,
            @RequestParam String destinationAirportCode,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate departureDate) {

        return flightService.searchFlights(
                originAirportCode,
                destinationAirportCode,
                departureDate);
    }

    @Operation(
            summary = "Route availability calendar",
            description = "Bookable-departure counts per day for a route over a capped date range - powers the fare-calendar date picker."
    )
    @GetMapping("/calendar")
    public List<com.skybook.praveen.flightservice.dto.response.RouteCalendarDayResponse> getRouteCalendar(
            @RequestParam String originAirportCode,
            @RequestParam String destinationAirportCode,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate startDate,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate endDate) {

        return flightService.getRouteCalendar(
                originAirportCode,
                destinationAirportCode,
                startDate,
                endDate);
    }

    @Operation(
            summary = "Get Flights By Status",
            description = "Returns all flights for the given status."
    )
    @GetMapping("/status/{status}")
    public List<FlightResponse> getFlightsByStatus(
            @PathVariable FlightStatus status) {

        return flightService.getFlightsByStatus(status);
    }

    @Operation(
            summary = "Get Flights By Departure Date",
            description = "Returns all flights departing on the specified date."
    )
    @GetMapping("/departure-date")
    public List<FlightResponse> getFlightsByDepartureDate(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate departureDate) {

        return flightService.getFlightsByDepartureDate(departureDate);
    }

    @Operation(
            summary = "Get Flights By Date Range",
            description = "Returns all flights departing between two dates."
    )
    @GetMapping("/departure-date-range")
    public List<FlightResponse> getFlightsByDepartureDateRange(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate startDate,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate endDate) {

        return flightService.getFlightsByDepartureDateRange(
                startDate,
                endDate);
    }

    // =====================================================
    // UPDATE
    // =====================================================

    @Operation(
            summary = "Update Flight",
            description = "Updates flight information."
    )
    @PutMapping("/{id}")
    public FlightResponse updateFlight(
            @PathVariable Long id,
            @Valid @RequestBody UpdateFlightRequest request) {

        return flightService.updateFlight(id, request);
    }

    @Operation(
            summary = "Update Flight Status",
            description = "Updates the status of a flight."
    )
    @PatchMapping("/{id}/status")
    public FlightResponse updateFlightStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateFlightStatusRequest request) {

        return flightService.updateFlightStatus(
                id,
                request.status());
    }

    @Operation(
            summary = "Delay Flight",
            description = "Updates departure and arrival times because of a delay."
    )
    @PatchMapping("/{id}/delay")
    public FlightResponse delayFlight(
            @PathVariable Long id,
            @Valid @RequestBody DelayFlightRequest request) {

        return flightService.delayFlight(
                id,
                request.newDepartureTime(),
                request.newArrivalTime());
    }

    @Operation(
            summary = "Reschedule Flight",
            description = "Reschedules a flight."
    )
    @PatchMapping("/{id}/reschedule")
    public FlightResponse rescheduleFlight(
            @PathVariable Long id,
            @Valid @RequestBody RescheduleFlightRequest request) {

        return flightService.rescheduleFlight(
                id,
                request.departureTime(),
                request.arrivalTime());
    }

    @Operation(
            summary = "Cancel Flight",
            description = "Cancels a flight."
    )
    @PatchMapping("/{id}/cancel")
    public FlightResponse cancelFlight(
            @PathVariable Long id) {

        return flightService.cancelFlight(id);
    }

    @Operation(
            summary = "Start Boarding",
            description = "Marks a flight as boarding."
    )
    @PatchMapping("/{id}/board")
    public FlightResponse boardFlight(
            @PathVariable Long id) {

        return flightService.boardFlight(id);
    }

    @Operation(
            summary = "Depart Flight",
            description = "Marks a flight as departed."
    )
    @PatchMapping("/{id}/depart")
    public FlightResponse departFlight(
            @PathVariable Long id) {

        return flightService.departFlight(id);
    }

    @Operation(
            summary = "Arrive Flight",
            description = "Marks a flight as arrived."
    )
    @PatchMapping("/{id}/arrive")
    public FlightResponse arriveFlight(
            @PathVariable Long id) {

        return flightService.arriveFlight(id);
    }

    // =====================================================
    // DELETE
    // =====================================================

    @Operation(
            summary = "Delete Flight",
            description = "Deletes a flight."
    )
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFlight(
            @PathVariable Long id) {

        flightService.deleteFlight(id);

        return ResponseEntity.noContent().build();
    }
}