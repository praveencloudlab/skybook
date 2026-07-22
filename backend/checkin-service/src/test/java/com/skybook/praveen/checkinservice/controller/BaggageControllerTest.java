package com.skybook.praveen.checkinservice.controller;

import com.skybook.praveen.checkinservice.config.WebSliceSecurityConfig;
import org.springframework.security.test.context.support.WithMockUser;
import com.skybook.praveen.checkinservice.dto.request.CreateBaggageRequest;
import com.skybook.praveen.checkinservice.dto.response.BaggageResponse;
import com.skybook.praveen.checkinservice.service.BaggageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BaggageController.class,
        excludeAutoConfiguration = com.skybook.praveen.security.JwtSecurityAutoConfiguration.class)
@Import(WebSliceSecurityConfig.class)
@WithMockUser(roles = "ADMIN")
class BaggageControllerTest {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.skybook.praveen.checkinservice.security.CheckInAccessGuard accessGuard;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BaggageService baggageService;

    private BaggageResponse response() {
        return new BaggageResponse(5L, 1L, "BAG-2026-ABCDEF", new BigDecimal("18"), false, null);
    }

    @Test
    void addBaggageReturns201() throws Exception {
        when(baggageService.addBaggage(any(CreateBaggageRequest.class))).thenReturn(response());

        mockMvc.perform(post("/api/baggage")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"checkInId":1,"weightKg":18}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tagNumber").value("BAG-2026-ABCDEF"));
    }

    @Test
    void addBaggageWithZeroWeightReturns400() throws Exception {
        mockMvc.perform(post("/api/baggage")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"checkInId":1,"weightKg":0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addBaggageForNonCheckedInPassengerReturns409() throws Exception {
        when(baggageService.addBaggage(any(CreateBaggageRequest.class)))
                .thenThrow(new IllegalStateException("Check-in 1 is OPEN - cannot add baggage"));

        mockMvc.perform(post("/api/baggage")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"checkInId":1,"weightKg":18}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void getByCheckInIdReturns200() throws Exception {
        when(baggageService.getByCheckInId(1L)).thenReturn(List.of(response()));

        mockMvc.perform(get("/api/baggage/checkin/1")).andExpect(status().isOk());
    }
}
