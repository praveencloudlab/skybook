package com.skybook.praveen.flightservice.controller;

import com.skybook.praveen.flightservice.config.WebSliceSecurityConfig;
import com.skybook.praveen.flightservice.dto.request.CreateFlightRequest;
import com.skybook.praveen.flightservice.dto.request.UpdateFlightRequest;
import com.skybook.praveen.flightservice.dto.response.FlightResponse;
import com.skybook.praveen.flightservice.dto.response.ItineraryResponse;
import com.skybook.praveen.flightservice.dto.response.RouteCalendarDayResponse;
import com.skybook.praveen.flightservice.enums.FlightStatus;
import com.skybook.praveen.flightservice.exception.FlightNotFoundException;
import com.skybook.praveen.flightservice.service.FlightService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice: routing, request binding/validation and the status codes
 * each endpoint hands back, including the ones produced by
 * GlobalExceptionHandler. The service itself is mocked - its behaviour is
 * covered by FlightServiceImplTest.
 */
@WebMvcTest(FlightController.class)
@Import(WebSliceSecurityConfig.class)
class FlightControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FlightService flightService;

    private static final LocalDateTime DEPARTURE = LocalDateTime.now().plusDays(30).withNano(0);
    private static final LocalDateTime ARRIVAL = DEPARTURE.plusHours(8);

    private FlightResponse response(FlightStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return new FlightResponse(1L, "BA178", "BA", "LHR", "JFK", DEPARTURE, ARRIVAL,
                "5", "8", status, null, "system", "system", 0L, now, now);
    }

    private String createBody(String origin, String destination) {
        return """
                {"flightNumber":"BA178","airlineCode":"BA","originAirportCode":"%s",
                 "destinationAirportCode":"%s","departureTime":"%s","arrivalTime":"%s"}
                """.formatted(origin, destination, DEPARTURE, ARRIVAL);
    }

    // =====================================================
    // CREATE
    // =====================================================

    @Test
    void createReturns201WithTheCreatedFlight() throws Exception {
        when(flightService.createFlight(any(CreateFlightRequest.class)))
                .thenReturn(response(FlightStatus.SCHEDULED));

        mockMvc.perform(post("/api/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("LHR", "JFK")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flightNumber").value("BA178"))
                .andExpect(jsonPath("$.departureTerminal").value("5"))
                .andExpect(jsonPath("$.arrivalTerminal").value("8"))
                .andExpect(jsonPath("$.status").value("SCHEDULED"));
    }

    @Test
    void createWithAMissingFieldReturns400WithTheFieldName() throws Exception {
        mockMvc.perform(post("/api/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"","airlineCode":"BA","originAirportCode":"LHR",
                                 "destinationAirportCode":"JFK","departureTime":"%s","arrivalTime":"%s"}
                                """.formatted(DEPARTURE, ARRIVAL)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("flightNumber")));
    }

    @Test
    void createWithAPastDepartureReturns400() throws Exception {
        LocalDateTime past = LocalDateTime.now().minusDays(1).withNano(0);

        mockMvc.perform(post("/api/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"BA178","airlineCode":"BA","originAirportCode":"LHR",
                                 "destinationAirportCode":"JFK","departureTime":"%s","arrivalTime":"%s"}
                                """.formatted(past, ARRIVAL)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createWithADuplicateReturns400FromTheService() throws Exception {
        when(flightService.createFlight(any(CreateFlightRequest.class)))
                .thenThrow(new IllegalArgumentException("Flight BA178 already exists for departure time"));

        mockMvc.perform(post("/api/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("LHR", "JFK")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Flight BA178 already exists for departure time"))
                .andExpect(jsonPath("$.path").value("/api/flights"));
    }

    @Test
    void bulkCreateReturns201WithEveryFlight() throws Exception {
        when(flightService.createFlights(anyList()))
                .thenReturn(List.of(response(FlightStatus.SCHEDULED), response(FlightStatus.SCHEDULED)));

        mockMvc.perform(post("/api/flights/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[%s,%s]".formatted(createBody("LHR", "JFK"), createBody("LHR", "DXB"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // =====================================================
    // READ
    // =====================================================

    @Test
    void getByIdReturns200() throws Exception {
        when(flightService.getFlightById(1L)).thenReturn(response(FlightStatus.SCHEDULED));

        mockMvc.perform(get("/api/flights/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void missingFlightReturns404WithTheErrorContract() throws Exception {
        when(flightService.getFlightById(42L)).thenThrow(new FlightNotFoundException(42L));

        mockMvc.perform(get("/api/flights/42"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Flight not found with id: 42"))
                .andExpect(jsonPath("$.path").value("/api/flights/42"));
    }

    @Test
    void getAllReturns200WithTheList() throws Exception {
        when(flightService.getAllFlights()).thenReturn(List.of(response(FlightStatus.SCHEDULED)));

        mockMvc.perform(get("/api/flights"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void searchBindsTheRouteAndIsoDate() throws Exception {
        when(flightService.searchFlights("LHR", "JFK", LocalDate.of(2026, 9, 10)))
                .thenReturn(List.of(response(FlightStatus.SCHEDULED)));

        mockMvc.perform(get("/api/flights/search")
                        .param("originAirportCode", "LHR")
                        .param("destinationAirportCode", "JFK")
                        .param("departureDate", "2026-09-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void searchWithoutADateReturns400() throws Exception {
        mockMvc.perform(get("/api/flights/search")
                        .param("originAirportCode", "LHR")
                        .param("destinationAirportCode", "JFK"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void itinerariesReturnStopsLayoversAndTheThroughTicketFlag() throws Exception {
        ItineraryResponse oneStop = new ItineraryResponse(
                List.of(response(FlightStatus.SCHEDULED), response(FlightStatus.SCHEDULED)),
                1, 1140L, List.of(60L), true);
        when(flightService.getItineraries("LHR", "JFK", LocalDate.of(2026, 9, 10)))
                .thenReturn(List.of(oneStop));

        mockMvc.perform(get("/api/flights/itineraries")
                        .param("originAirportCode", "LHR")
                        .param("destinationAirportCode", "JFK")
                        .param("departureDate", "2026-09-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stops").value(1))
                .andExpect(jsonPath("$[0].legs.length()").value(2))
                .andExpect(jsonPath("$[0].layoverMinutes[0]").value(60))
                .andExpect(jsonPath("$[0].totalDurationMinutes").value(1140))
                .andExpect(jsonPath("$[0].sameCarrier").value(true));
    }

    @Test
    void calendarReturnsPerDayCounts() throws Exception {
        when(flightService.getRouteCalendar("LHR", "JFK",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
                .thenReturn(List.of(new RouteCalendarDayResponse(LocalDate.of(2026, 9, 1), 3)));

        mockMvc.perform(get("/api/flights/calendar")
                        .param("originAirportCode", "LHR")
                        .param("destinationAirportCode", "JFK")
                        .param("startDate", "2026-09-01")
                        .param("endDate", "2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].date").value("2026-09-01"))
                .andExpect(jsonPath("$[0].flights").value(3));
    }

    @Test
    void anOversizedCalendarRangeReturns400() throws Exception {
        when(flightService.getRouteCalendar(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Calendar range must not exceed 124 days"));

        mockMvc.perform(get("/api/flights/calendar")
                        .param("originAirportCode", "LHR")
                        .param("destinationAirportCode", "JFK")
                        .param("startDate", "2026-09-01")
                        .param("endDate", "2027-09-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Calendar range must not exceed 124 days"));
    }

    @Test
    void byStatusBindsTheEnumPathVariable() throws Exception {
        when(flightService.getFlightsByStatus(FlightStatus.CANCELLED))
                .thenReturn(List.of(response(FlightStatus.CANCELLED)));

        mockMvc.perform(get("/api/flights/status/CANCELLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("CANCELLED"));
    }

    @Test
    void byDepartureDateReturns200() throws Exception {
        when(flightService.getFlightsByDepartureDate(LocalDate.of(2026, 9, 10)))
                .thenReturn(List.of(response(FlightStatus.SCHEDULED)));

        mockMvc.perform(get("/api/flights/departure-date").param("departureDate", "2026-09-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void byDepartureDateRangeReturns200() throws Exception {
        when(flightService.getFlightsByDepartureDateRange(
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 12)))
                .thenReturn(List.of(response(FlightStatus.SCHEDULED)));

        mockMvc.perform(get("/api/flights/departure-date-range")
                        .param("startDate", "2026-09-10")
                        .param("endDate", "2026-09-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // =====================================================
    // UPDATE
    // =====================================================

    @Test
    void updateReturns200() throws Exception {
        when(flightService.updateFlight(eq(1L), any(UpdateFlightRequest.class)))
                .thenReturn(response(FlightStatus.SCHEDULED));

        mockMvc.perform(put("/api/flights/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"airlineCode":"BA","originAirportCode":"LHR","destinationAirportCode":"JFK",
                                 "departureTime":"%s","arrivalTime":"%s"}
                                """.formatted(DEPARTURE, ARRIVAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void updateWithABlankAirlineReturns400() throws Exception {
        mockMvc.perform(put("/api/flights/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"airlineCode":"","originAirportCode":"LHR","destinationAirportCode":"JFK",
                                 "departureTime":"%s","arrivalTime":"%s"}
                                """.formatted(DEPARTURE, ARRIVAL)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void statusPatchReturns200() throws Exception {
        when(flightService.updateFlightStatus(1L, FlightStatus.DELAYED))
                .thenReturn(response(FlightStatus.DELAYED));

        mockMvc.perform(patch("/api/flights/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DELAYED","reason":"Weather","remarks":"Fog at LHR"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELAYED"));
    }

    @Test
    void statusPatchWithoutAStatusReturns400() throws Exception {
        mockMvc.perform(patch("/api/flights/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"Weather"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void delayPatchReturns200() throws Exception {
        when(flightService.delayFlight(1L, DEPARTURE.plusHours(2), ARRIVAL.plusHours(2)))
                .thenReturn(response(FlightStatus.DELAYED));

        mockMvc.perform(patch("/api/flights/1/delay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newDepartureTime":"%s","newArrivalTime":"%s","reason":"Late inbound aircraft"}
                                """.formatted(DEPARTURE.plusHours(2), ARRIVAL.plusHours(2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELAYED"));
    }

    @Test
    void delayWithoutAReasonReturns400() throws Exception {
        mockMvc.perform(patch("/api/flights/1/delay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newDepartureTime":"%s","newArrivalTime":"%s","reason":" "}
                                """.formatted(DEPARTURE.plusHours(2), ARRIVAL.plusHours(2))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reschedulePatchReturns200() throws Exception {
        when(flightService.rescheduleFlight(1L, DEPARTURE.plusDays(1), ARRIVAL.plusDays(1)))
                .thenReturn(response(FlightStatus.SCHEDULED));

        mockMvc.perform(patch("/api/flights/1/reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"departureTime":"%s","arrivalTime":"%s","remarks":"Slot change"}
                                """.formatted(DEPARTURE.plusDays(1), ARRIVAL.plusDays(1))))
                .andExpect(status().isOk());
    }

    @Test
    void rescheduleIntoThePastReturns400() throws Exception {
        LocalDateTime past = LocalDateTime.now().minusDays(2).withNano(0);

        mockMvc.perform(patch("/api/flights/1/reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"departureTime":"%s","arrivalTime":"%s"}
                                """.formatted(past, past.plusHours(2))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancelPatchReturns200() throws Exception {
        when(flightService.cancelFlight(1L)).thenReturn(response(FlightStatus.CANCELLED));

        mockMvc.perform(patch("/api/flights/1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancellingAMissingFlightReturns404() throws Exception {
        when(flightService.cancelFlight(42L)).thenThrow(new FlightNotFoundException(42L));

        mockMvc.perform(patch("/api/flights/42/cancel"))
                .andExpect(status().isNotFound());
    }

    @Test
    void boardDepartAndArrivePatchesReturn200() throws Exception {
        when(flightService.boardFlight(1L)).thenReturn(response(FlightStatus.BOARDING));
        when(flightService.departFlight(1L)).thenReturn(response(FlightStatus.DEPARTED));
        when(flightService.arriveFlight(1L)).thenReturn(response(FlightStatus.ARRIVED));

        mockMvc.perform(patch("/api/flights/1/board"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOARDING"));
        mockMvc.perform(patch("/api/flights/1/depart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEPARTED"));
        mockMvc.perform(patch("/api/flights/1/arrive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"));
    }

    // =====================================================
    // DELETE
    // =====================================================

    @Test
    void deleteReturns204WithNoBody() throws Exception {
        mockMvc.perform(delete("/api/flights/1"))
                .andExpect(status().isNoContent());

        verify(flightService).deleteFlight(1L);
    }

    @Test
    void deletingAMissingFlightReturns404() throws Exception {
        doThrow(new FlightNotFoundException(42L)).when(flightService).deleteFlight(42L);

        mockMvc.perform(delete("/api/flights/42"))
                .andExpect(status().isNotFound());
    }
}
