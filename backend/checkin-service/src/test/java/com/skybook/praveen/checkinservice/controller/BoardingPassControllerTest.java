package com.skybook.praveen.checkinservice.controller;

import com.skybook.praveen.checkinservice.config.WebSliceSecurityConfig;
import org.springframework.security.test.context.support.WithMockUser;
import com.skybook.praveen.checkinservice.dto.response.BoardingPassResponse;
import com.skybook.praveen.checkinservice.dto.response.BoardingPassVerifyResponse;
import com.skybook.praveen.checkinservice.enums.BoardingPassStatus;
import com.skybook.praveen.checkinservice.exception.BoardingPassNotFoundException;
import com.skybook.praveen.checkinservice.exception.BoardingPassVerificationException;
import com.skybook.praveen.checkinservice.service.BoardingPassService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BoardingPassController.class,
        excludeAutoConfiguration = com.skybook.praveen.security.JwtSecurityAutoConfiguration.class)
@Import(WebSliceSecurityConfig.class)
@WithMockUser(roles = "ADMIN")
class BoardingPassControllerTest {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.skybook.praveen.checkinservice.security.CheckInAccessGuard accessGuard;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BoardingPassService boardingPassService;

    private BoardingPassResponse response() {
        LocalDateTime now = LocalDateTime.now();
        return new BoardingPassResponse(10L, 1L, "BP-2026-K7M4Z9", "token", "Test Passenger", "SBTEST",
                "BA178", "LHR", "JFK", "12B", "A12", now, "3", BoardingPassStatus.ACTIVE, now, null, null);
    }

    @Test
    void getByIdReturns200() throws Exception {
        when(boardingPassService.getById(10L)).thenReturn(response());

        mockMvc.perform(get("/api/boarding-passes/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boardingPassNumber").value("BP-2026-K7M4Z9"));
    }

    @Test
    void getByIdMissingReturns404() throws Exception {
        when(boardingPassService.getById(99L)).thenThrow(BoardingPassNotFoundException.byId(99L));

        mockMvc.perform(get("/api/boarding-passes/99")).andExpect(status().isNotFound());
    }

    @Test
    void getActiveForCheckInReturns200() throws Exception {
        when(boardingPassService.getActiveForCheckIn(1L)).thenReturn(response());

        mockMvc.perform(get("/api/boarding-passes/checkin/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boardingPassNumber").value("BP-2026-K7M4Z9"));
    }

    @Test
    void getActiveForCheckInBeforeCheckInReturns404() throws Exception {
        when(boardingPassService.getActiveForCheckIn(1L)).thenThrow(BoardingPassNotFoundException.byCheckIn(1L));

        mockMvc.perform(get("/api/boarding-passes/checkin/1")).andExpect(status().isNotFound());
    }

    @Test
    void verifySucceedsReturns200() throws Exception {
        when(boardingPassService.verify("valid-token")).thenReturn(
                new BoardingPassVerifyResponse("Test Passenger", "SBTEST", "BA178", "12B", "A12", "3"));

        mockMvc.perform(get("/api/boarding-passes/verify").param("token", "valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passengerName").value("Test Passenger"))
                .andExpect(jsonPath("$.gate").value("A12"));
    }

    @Test
    void verifyFailureReturns422() throws Exception {
        when(boardingPassService.verify("bad-token"))
                .thenThrow(new BoardingPassVerificationException("tampered or malformed token"));

        mockMvc.perform(get("/api/boarding-passes/verify").param("token", "bad-token"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        "Boarding pass verification failed: tampered or malformed token"));
    }
}
