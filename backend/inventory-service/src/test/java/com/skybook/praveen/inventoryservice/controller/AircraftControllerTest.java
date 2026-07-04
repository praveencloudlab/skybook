package com.skybook.praveen.inventoryservice.controller;

import com.skybook.praveen.inventoryservice.config.SecurityConfig;
import com.skybook.praveen.inventoryservice.dto.request.CreateAircraftRequest;
import com.skybook.praveen.inventoryservice.dto.response.AircraftResponse;
import com.skybook.praveen.inventoryservice.enums.AircraftStatus;
import com.skybook.praveen.inventoryservice.exception.AircraftNotFoundException;
import com.skybook.praveen.inventoryservice.exception.InventoryConflictException;
import com.skybook.praveen.inventoryservice.service.AircraftService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AircraftController.class)
@Import(SecurityConfig.class)
class AircraftControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AircraftService aircraftService;

    private AircraftResponse response(AircraftStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return new AircraftResponse(1L, "VT-SKB", "Airbus", "A320neo", 0, status,
                "system", "system", 0L, now, now);
    }

    @Test
    void createReturns201WithBody() throws Exception {
        when(aircraftService.createAircraft(any(CreateAircraftRequest.class)))
                .thenReturn(response(AircraftStatus.ACTIVE));

        mockMvc.perform(post("/api/aircraft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"registrationNumber":"VT-SKB","manufacturer":"Airbus","model":"A320neo"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.registrationNumber").value("VT-SKB"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void invalidBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/aircraft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"registrationNumber":"vt-lowercase!","manufacturer":"","model":"A320neo"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateRegistrationReturns409() throws Exception {
        when(aircraftService.createAircraft(any(CreateAircraftRequest.class)))
                .thenThrow(new InventoryConflictException("Aircraft already exists with registration number: VT-SKB"));

        mockMvc.perform(post("/api/aircraft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"registrationNumber":"VT-SKB","manufacturer":"Airbus","model":"A320neo"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Aircraft already exists with registration number: VT-SKB"));
    }

    @Test
    void getByIdReturns200() throws Exception {
        when(aircraftService.getAircraftById(1L)).thenReturn(response(AircraftStatus.ACTIVE));

        mockMvc.perform(get("/api/aircraft/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void missingAircraftReturns404WithErrorContract() throws Exception {
        when(aircraftService.getAircraftById(42L)).thenThrow(new AircraftNotFoundException(42L));

        mockMvc.perform(get("/api/aircraft/42"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/api/aircraft/42"));
    }

    @Test
    void statusUpdateReturns200() throws Exception {
        when(aircraftService.updateAircraftStatus(1L, AircraftStatus.MAINTENANCE))
                .thenReturn(response(AircraftStatus.MAINTENANCE));

        mockMvc.perform(patch("/api/aircraft/1/status").param("status", "MAINTENANCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MAINTENANCE"));
    }
}
