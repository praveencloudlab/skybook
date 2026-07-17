package com.skybook.praveen.inventoryservice.controller;

import com.skybook.praveen.inventoryservice.config.SecurityConfig;
import com.skybook.praveen.inventoryservice.dto.request.CreateAircraftSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.CreateSeatMapRequest;
import com.skybook.praveen.inventoryservice.dto.response.AircraftSeatResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatMapResponse;
import com.skybook.praveen.inventoryservice.enums.AircraftSeatStatus;
import com.skybook.praveen.inventoryservice.enums.AircraftStatus;
import com.skybook.praveen.inventoryservice.enums.SeatPosition;
import com.skybook.praveen.inventoryservice.enums.SeatType;
import com.skybook.praveen.inventoryservice.service.AircraftSeatService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AircraftSeatController.class)
@Import(SecurityConfig.class)
class AircraftSeatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AircraftSeatService aircraftSeatService;

    private AircraftSeatResponse seatResponse(String seatNumber) {
        return new AircraftSeatResponse(2L, seatNumber, 12, SeatType.ECONOMY,
                SeatPosition.WINDOW, AircraftSeatStatus.ACTIVE, false, new BigDecimal("12.00"));
    }

    @Test
    void addSeatReturns201() throws Exception {
        when(aircraftSeatService.addSeat(eq(1L), any(CreateAircraftSeatRequest.class)))
                .thenReturn(seatResponse("12A"));

        mockMvc.perform(post("/api/aircraft/1/seats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seatNumber":"12A","rowNumber":12,"seatType":"ECONOMY","position":"WINDOW"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seatNumber").value("12A"));
    }

    @Test
    void invalidSeatNumberPatternReturns400() throws Exception {
        mockMvc.perform(post("/api/aircraft/1/seats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seatNumber":"12Z","rowNumber":12,"seatType":"ECONOMY","position":"WINDOW"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateSeatReturns400() throws Exception {
        when(aircraftSeatService.addSeat(eq(1L), any(CreateAircraftSeatRequest.class)))
                .thenThrow(new IllegalArgumentException("Seat 12A already exists on aircraft VT-SKB"));

        mockMvc.perform(post("/api/aircraft/1/seats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seatNumber":"12A","rowNumber":12,"seatType":"ECONOMY","position":"WINDOW"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Seat 12A already exists on aircraft VT-SKB"));
    }

    @Test
    void seatMapCreateReturns201WithAllSeats() throws Exception {
        when(aircraftSeatService.createSeatMap(eq(1L), any(CreateSeatMapRequest.class)))
                .thenReturn(List.of(seatResponse("12A"), seatResponse("12B")));

        mockMvc.perform(post("/api/aircraft/1/seat-map")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seats":[
                                  {"seatNumber":"12A","rowNumber":12,"seatType":"ECONOMY","position":"WINDOW"},
                                  {"seatNumber":"12B","rowNumber":12,"seatType":"ECONOMY","position":"MIDDLE"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void emptySeatMapReturns400() throws Exception {
        mockMvc.perform(post("/api/aircraft/1/seat-map")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seats":[]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getSeatMapReturns200() throws Exception {
        when(aircraftSeatService.getSeatMap(1L)).thenReturn(new SeatMapResponse(
                1L, "VT-SKB", "A320neo", AircraftStatus.ACTIVE, 1, List.of(seatResponse("12A"))));

        mockMvc.perform(get("/api/aircraft/1/seat-map"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationNumber").value("VT-SKB"))
                .andExpect(jsonPath("$.seats[0].seatNumber").value("12A"));
    }
}
