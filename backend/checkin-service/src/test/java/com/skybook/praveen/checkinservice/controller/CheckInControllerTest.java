package com.skybook.praveen.checkinservice.controller;

import com.skybook.praveen.checkinservice.config.SecurityConfig;
import com.skybook.praveen.checkinservice.dto.request.CreateCheckInRequest;
import com.skybook.praveen.checkinservice.dto.response.CheckInResponse;
import com.skybook.praveen.checkinservice.enums.CheckInStatus;
import com.skybook.praveen.checkinservice.exception.CheckInNotFoundException;
import com.skybook.praveen.checkinservice.facade.CheckInFacade;
import com.skybook.praveen.checkinservice.service.CheckInService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CheckInController.class)
@Import(SecurityConfig.class)
class CheckInControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CheckInService checkInService;
    @MockitoBean
    private CheckInFacade checkInFacade;

    private CheckInResponse response(CheckInStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return new CheckInResponse(1L, 42L, "SBTEST", 100L, 7L, "BA178", "LHR", "JFK", now.plusHours(2),
                "Test Passenger", "12B", "ECONOMY", "FLEXI", status, true, null, null, null, null, 0L, now, now);
    }

    private static final String CREATE_BODY = """
            {"bookingId":42,"bookingReference":"SBTEST","bookingPassengerId":100,"flightId":7,
             "passengerName":"Test Passenger","documentVerified":true}
            """;

    @Test
    void createReturns201() throws Exception {
        when(checkInService.createCheckIn(any(CreateCheckInRequest.class), any(), any(), any()))
                .thenReturn(response(CheckInStatus.NOT_OPEN));

        mockMvc.perform(post("/api/checkins")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NOT_OPEN"));
    }

    @Test
    void createWithoutPassengerNameReturns400() throws Exception {
        mockMvc.perform(post("/api/checkins")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookingId":42,"bookingReference":"SBTEST","bookingPassengerId":100,"flightId":7}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getByIdReturns200() throws Exception {
        when(checkInService.getById(1L)).thenReturn(response(CheckInStatus.OPEN));

        mockMvc.perform(get("/api/checkins/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingReference").value("SBTEST"));
    }

    @Test
    void getByIdMissingReturns404() throws Exception {
        when(checkInService.getById(99L)).thenThrow(CheckInNotFoundException.byId(99L));

        mockMvc.perform(get("/api/checkins/99")).andExpect(status().isNotFound());
    }

    @Test
    void getByBookingIdReturns200() throws Exception {
        when(checkInService.getByBookingId(42L)).thenReturn(List.of(response(CheckInStatus.OPEN)));
        mockMvc.perform(get("/api/checkins/booking/42")).andExpect(status().isOk());
    }

    @Test
    void getByFlightIdReturns200() throws Exception {
        when(checkInService.getByFlightId(7L)).thenReturn(List.of(response(CheckInStatus.OPEN)));
        mockMvc.perform(get("/api/checkins/flight/7")).andExpect(status().isOk());
    }

    @Test
    void openWindowReturns200() throws Exception {
        when(checkInService.openWindow(1L)).thenReturn(response(CheckInStatus.OPEN));

        mockMvc.perform(patch("/api/checkins/1/open"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void checkInReturns200() throws Exception {
        when(checkInFacade.checkIn(1L)).thenReturn(response(CheckInStatus.CHECKED_IN));

        mockMvc.perform(patch("/api/checkins/1/checkin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_IN"));
    }

    @Test
    void checkInWithCancelledFlightReturns409() throws Exception {
        when(checkInFacade.checkIn(1L)).thenThrow(new IllegalStateException("Cannot proceed - flight 7 is cancelled"));

        mockMvc.perform(patch("/api/checkins/1/checkin")).andExpect(status().isConflict());
    }

    @Test
    void boardReturns200() throws Exception {
        when(checkInFacade.board(1L)).thenReturn(response(CheckInStatus.BOARDED));

        mockMvc.perform(patch("/api/checkins/1/board"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOARDED"));
    }

    @Test
    void changeSeatReturns200() throws Exception {
        when(checkInFacade.changeSeat(1L, "14C")).thenReturn(response(CheckInStatus.CHECKED_IN));

        mockMvc.perform(patch("/api/checkins/1/seat")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"newSeatNumber":"14C"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void changeSeatWithBlankSeatNumberReturns400() throws Exception {
        mockMvc.perform(patch("/api/checkins/1/seat")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"newSeatNumber":""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void assignGateReturns200() throws Exception {
        when(checkInService.assignGate(1L, "A12")).thenReturn(response(CheckInStatus.CHECKED_IN));

        mockMvc.perform(patch("/api/checkins/1/gate")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"gate":"A12"}
                                """))
                .andExpect(status().isOk());
    }
}
