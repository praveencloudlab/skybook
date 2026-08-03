package com.skybook.praveen.flightservice.controller;

import com.skybook.praveen.flightservice.config.WebSliceSecurityConfig;
import com.skybook.praveen.flightservice.dto.request.CreateFlightScheduleRequest;
import com.skybook.praveen.flightservice.dto.response.FlightResponse;
import com.skybook.praveen.flightservice.dto.response.FlightScheduleResponse;
import com.skybook.praveen.flightservice.enums.FlightStatus;
import com.skybook.praveen.flightservice.enums.ScheduleStatus;
import com.skybook.praveen.flightservice.exception.FlightScheduleNotFoundException;
import com.skybook.praveen.flightservice.service.FlightScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice for the schedule admin surface: binding, the optional
 * pause/cancel bodies, and the 404/409/400 the handler turns service failures
 * into.
 */
@WebMvcTest(FlightScheduleController.class)
@Import(WebSliceSecurityConfig.class)
class FlightScheduleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FlightScheduleService flightScheduleService;

    private static final LocalDate VALID_FROM = LocalDate.now().plusDays(1);
    private static final LocalDate VALID_TO = LocalDate.now().plusMonths(3);

    private FlightScheduleResponse response(ScheduleStatus status, String reason, String remarks) {
        LocalDateTime now = LocalDateTime.now();
        Set<DayOfWeek> days = EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
        return new FlightScheduleResponse(7L, "SCH-LHR-JFK-000007", "BA178", "BA", "LHR", "JFK",
                LocalTime.of(10, 15), LocalTime.of(13, 40), days, VALID_FROM, VALID_TO,
                status, null, 30, reason, remarks, "system", "system", 0L, now, now);
    }

    private String createBody() {
        return """
                {"flightNumber":"BA178","airlineCode":"BA","originAirportCode":"LHR",
                 "destinationAirportCode":"JFK","departureTime":"10:15","arrivalTime":"13:40",
                 "operatingDays":["MONDAY","FRIDAY"],"validFrom":"%s","validTo":"%s",
                 "generationDaysAhead":30}
                """.formatted(VALID_FROM, VALID_TO);
    }

    @Test
    void createReturns201WithTheGeneratedScheduleCode() throws Exception {
        when(flightScheduleService.createSchedule(any(CreateFlightScheduleRequest.class)))
                .thenReturn(response(ScheduleStatus.ACTIVE, null, null));

        mockMvc.perform(post("/api/flight-schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scheduleCode").value("SCH-LHR-JFK-000007"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.operatingDays.length()").value(2));
    }

    @Test
    void createWithoutOperatingDaysReturns400() throws Exception {
        mockMvc.perform(post("/api/flight-schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"BA178","airlineCode":"BA","originAirportCode":"LHR",
                                 "destinationAirportCode":"JFK","departureTime":"10:15","arrivalTime":"13:40",
                                 "operatingDays":[],"validFrom":"%s"}
                                """.formatted(VALID_FROM)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("operatingDays")));
    }

    @Test
    void createWithAPastValidFromReturns400() throws Exception {
        mockMvc.perform(post("/api/flight-schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"BA178","airlineCode":"BA","originAirportCode":"LHR",
                                 "destinationAirportCode":"JFK","departureTime":"10:15","arrivalTime":"13:40",
                                 "operatingDays":["MONDAY"],"validFrom":"%s"}
                                """.formatted(LocalDate.now().minusDays(1))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createOfARouteToItselfReturns400FromTheService() throws Exception {
        when(flightScheduleService.createSchedule(any(CreateFlightScheduleRequest.class)))
                .thenThrow(new IllegalArgumentException("Origin and destination airports must be different"));

        mockMvc.perform(post("/api/flight-schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Origin and destination airports must be different"));
    }

    @Test
    void getByIdReturns200() throws Exception {
        when(flightScheduleService.getScheduleById(7L))
                .thenReturn(response(ScheduleStatus.ACTIVE, null, null));

        mockMvc.perform(get("/api/flight-schedules/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));
    }

    @Test
    void missingScheduleReturns404WithTheErrorContract() throws Exception {
        when(flightScheduleService.getScheduleById(42L))
                .thenThrow(new FlightScheduleNotFoundException(42L));

        mockMvc.perform(get("/api/flight-schedules/42"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Flight schedule not found with id: 42"))
                .andExpect(jsonPath("$.path").value("/api/flight-schedules/42"));
    }

    @Test
    void getAllReturns200() throws Exception {
        when(flightScheduleService.getAllSchedules())
                .thenReturn(List.of(response(ScheduleStatus.ACTIVE, null, null)));

        mockMvc.perform(get("/api/flight-schedules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void pauseForwardsTheReasonAndRemarks() throws Exception {
        when(flightScheduleService.pauseSchedule(7L, "Runway Maintenance", "27L resurfacing"))
                .thenReturn(response(ScheduleStatus.PAUSED, "Runway Maintenance", "27L resurfacing"));

        mockMvc.perform(patch("/api/flight-schedules/7/pause")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"Runway Maintenance","remarks":"27L resurfacing"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.statusReason").value("Runway Maintenance"));
    }

    @Test
    void pauseWorksWithNoBodyAtAll() throws Exception {
        when(flightScheduleService.pauseSchedule(eq(7L), isNull(), isNull()))
                .thenReturn(response(ScheduleStatus.PAUSED, null, null));

        mockMvc.perform(patch("/api/flight-schedules/7/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));

        verify(flightScheduleService).pauseSchedule(7L, null, null);
    }

    @Test
    void pausingACancelledScheduleReturns409() throws Exception {
        when(flightScheduleService.pauseSchedule(eq(7L), any(), any()))
                .thenThrow(new IllegalStateException("Cancelled schedules cannot be paused"));

        mockMvc.perform(patch("/api/flight-schedules/7/pause")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"Weather","remarks":null}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Cancelled schedules cannot be paused"));
    }

    @Test
    void resumeReturns200() throws Exception {
        when(flightScheduleService.resumeSchedule(7L))
                .thenReturn(response(ScheduleStatus.ACTIVE, null, null));

        mockMvc.perform(patch("/api/flight-schedules/7/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.statusReason").doesNotExist());
    }

    @Test
    void resumingAScheduleThatIsNotPausedReturns409() throws Exception {
        when(flightScheduleService.resumeSchedule(7L))
                .thenThrow(new IllegalStateException("Only paused schedules can be resumed"));

        mockMvc.perform(patch("/api/flight-schedules/7/resume"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only paused schedules can be resumed"));
    }

    @Test
    void cancelForwardsTheReasonAndRemarks() throws Exception {
        when(flightScheduleService.cancelSchedule(7L, "Route Withdrawn", "Loss making"))
                .thenReturn(response(ScheduleStatus.CANCELLED, "Route Withdrawn", "Loss making"));

        mockMvc.perform(patch("/api/flight-schedules/7/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"Route Withdrawn","remarks":"Loss making"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.statusRemarks").value("Loss making"));
    }

    @Test
    void cancelWorksWithNoBodyAtAll() throws Exception {
        when(flightScheduleService.cancelSchedule(eq(7L), isNull(), isNull()))
                .thenReturn(response(ScheduleStatus.CANCELLED, null, null));

        mockMvc.perform(patch("/api/flight-schedules/7/cancel"))
                .andExpect(status().isOk());

        verify(flightScheduleService).cancelSchedule(7L, null, null);
    }

    @Test
    void cancellingAMissingScheduleReturns404() throws Exception {
        when(flightScheduleService.cancelSchedule(eq(42L), any(), any()))
                .thenThrow(new FlightScheduleNotFoundException(42L));

        mockMvc.perform(patch("/api/flight-schedules/42/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"Route Withdrawn","remarks":null}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void extendReturns200() throws Exception {
        LocalDate newValidTo = LocalDate.now().plusMonths(6);
        when(flightScheduleService.extendSchedule(7L, newValidTo))
                .thenReturn(response(ScheduleStatus.ACTIVE, null, null));

        mockMvc.perform(patch("/api/flight-schedules/7/extend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newValidTo":"%s"}
                                """.formatted(newValidTo)))
                .andExpect(status().isOk());
    }

    @Test
    void extendingIntoThePastReturns400() throws Exception {
        mockMvc.perform(patch("/api/flight-schedules/7/extend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newValidTo":"%s"}
                                """.formatted(LocalDate.now().minusDays(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("newValidTo")));
    }

    @Test
    void extendingACancelledScheduleReturns409() throws Exception {
        LocalDate newValidTo = LocalDate.now().plusMonths(6);
        when(flightScheduleService.extendSchedule(7L, newValidTo))
                .thenThrow(new IllegalStateException("Cancelled schedules cannot be extended"));

        mockMvc.perform(patch("/api/flight-schedules/7/extend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newValidTo":"%s"}
                                """.formatted(newValidTo)))
                .andExpect(status().isConflict());
    }

    @Test
    void generateWithoutAHorizonUsesTheSchedulesOwn() throws Exception {
        when(flightScheduleService.generateFlights(eq(7L), isNull()))
                .thenReturn(List.of(flight()));

        mockMvc.perform(post("/api/flight-schedules/7/generate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        verify(flightScheduleService).generateFlights(7L, null);
    }

    @Test
    void generateHonoursAnExplicitHorizon() throws Exception {
        when(flightScheduleService.generateFlights(7L, 7)).thenReturn(List.of(flight(), flight()));

        mockMvc.perform(post("/api/flight-schedules/7/generate").param("horizonDays", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void generatingForAMissingScheduleReturns404() throws Exception {
        when(flightScheduleService.generateFlights(eq(42L), any()))
                .thenThrow(new FlightScheduleNotFoundException(42L));

        mockMvc.perform(post("/api/flight-schedules/42/generate"))
                .andExpect(status().isNotFound());
    }

    private FlightResponse flight() {
        LocalDateTime departure = VALID_FROM.atTime(10, 15);
        return new FlightResponse(1L, "BA178", "BA", "LHR", "JFK",
                departure, departure.plusHours(3), "5", "8",
                FlightStatus.SCHEDULED, 7L, "system", "system", 0L,
                LocalDateTime.now(), LocalDateTime.now());
    }
}
