package com.skybook.praveen.checkinservice.controller;

import com.skybook.praveen.checkinservice.config.SecurityConfig;
import com.skybook.praveen.checkinservice.dto.response.FlightManifestResponse;
import com.skybook.praveen.checkinservice.enums.ManifestStatus;
import com.skybook.praveen.checkinservice.service.ManifestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FlightManifestController.class)
@Import(SecurityConfig.class)
class FlightManifestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ManifestService manifestService;

    private FlightManifestResponse response(ManifestStatus status) {
        return new FlightManifestResponse(7L, status, status == ManifestStatus.FINALIZED ? LocalDateTime.now() : null,
                2, 1, 0, 0, BigDecimal.ZERO, List.of());
    }

    @Test
    void getManifestReturns200() throws Exception {
        when(manifestService.getManifest(7L)).thenReturn(response(ManifestStatus.OPEN));

        mockMvc.perform(get("/api/manifests/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.checkedInCount").value(2));
    }

    @Test
    void finalizeManifestReturns200() throws Exception {
        when(manifestService.finalizeManifest(eq(7L), any())).thenReturn(response(ManifestStatus.FINALIZED));

        mockMvc.perform(post("/api/manifests/7/finalize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINALIZED"));
    }

    @Test
    void finalizeManifestBeforeGateCloseReturns409() throws Exception {
        when(manifestService.finalizeManifest(eq(7L), any()))
                .thenThrow(new IllegalStateException("Manifest for flight 7 cannot be finalized before gate close"));

        mockMvc.perform(post("/api/manifests/7/finalize")).andExpect(status().isConflict());
    }
}
