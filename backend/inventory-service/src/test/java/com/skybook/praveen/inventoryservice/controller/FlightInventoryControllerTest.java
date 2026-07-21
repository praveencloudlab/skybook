package com.skybook.praveen.inventoryservice.controller;

import com.skybook.praveen.inventoryservice.config.WebSliceSecurityConfig;
import com.skybook.praveen.inventoryservice.dto.request.AutoHoldSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.CreateFlightInventoryRequest;
import com.skybook.praveen.inventoryservice.dto.request.HoldSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.InventorySearchRequest;
import com.skybook.praveen.inventoryservice.dto.request.ReleaseSeatRequest;
import com.skybook.praveen.inventoryservice.dto.response.FlightInventoryResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatHoldResponse;
import com.skybook.praveen.inventoryservice.enums.InventoryStatus;
import com.skybook.praveen.inventoryservice.enums.SeatAssignmentMode;
import com.skybook.praveen.inventoryservice.enums.SeatHoldStatus;
import com.skybook.praveen.inventoryservice.exception.FlightInventoryNotFoundException;
import com.skybook.praveen.inventoryservice.exception.FlightNotFoundForInventoryException;
import com.skybook.praveen.inventoryservice.exception.FlightServiceUnavailableException;
import com.skybook.praveen.inventoryservice.exception.InventoryConflictException;
import com.skybook.praveen.inventoryservice.exception.SeatAlreadyHeldException;
import com.skybook.praveen.inventoryservice.facade.InventoryFacade;
import com.skybook.praveen.inventoryservice.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FlightInventoryController.class)
@Import(WebSliceSecurityConfig.class)
class FlightInventoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InventoryFacade inventoryFacade;

    @MockitoBean
    private InventoryService inventoryService;

    private final LocalDateTime now = LocalDateTime.now();

    private FlightInventoryResponse inventoryResponse() {
        return new FlightInventoryResponse(10L, 100L, 1L, "VT-SKB", InventoryStatus.OPEN,
                3, 2, 1, 0, 0, 0L, now, now);
    }

    private SeatHoldResponse holdResponse() {
        return new SeatHoldResponse(5L, 100L, 2L, "12A", 42L, 420L, SeatAssignmentMode.MANUAL,
                new BigDecimal("12.00"), new BigDecimal("12.00"),
                SeatHoldStatus.ACTIVE, now, now.plusMinutes(15));
    }

    private SeatHoldResponse autoHoldResponse() {
        return new SeatHoldResponse(6L, 100L, 3L, "20B", 42L, 420L, SeatAssignmentMode.AUTO,
                new BigDecimal("12.00"), new BigDecimal("0.00"),
                SeatHoldStatus.ACTIVE, now, now.plusMinutes(15));
    }

    @Test
    void createReturns201() throws Exception {
        when(inventoryFacade.createInventory(any(CreateFlightInventoryRequest.class)))
                .thenReturn(inventoryResponse());

        mockMvc.perform(post("/api/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightId":100,"aircraftId":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flightId").value(100))
                .andExpect(jsonPath("$.availableSeats").value(2));
    }

    @Test
    void duplicateInventoryReturns409() throws Exception {
        when(inventoryFacade.createInventory(any(CreateFlightInventoryRequest.class)))
                .thenThrow(new InventoryConflictException("Inventory already exists for flight id: 100"));

        mockMvc.perform(post("/api/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightId":100,"aircraftId":1}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownFlightReturns404AndFlightServiceDownReturns502() throws Exception {
        when(inventoryFacade.createInventory(any(CreateFlightInventoryRequest.class)))
                .thenThrow(new FlightNotFoundForInventoryException(100L));
        mockMvc.perform(post("/api/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightId":100,"aircraftId":1}
                                """))
                .andExpect(status().isNotFound());

        when(inventoryFacade.createInventory(any(CreateFlightInventoryRequest.class)))
                .thenThrow(new FlightServiceUnavailableException(100L, new RuntimeException("timeout")));
        mockMvc.perform(post("/api/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightId":100,"aircraftId":1}
                                """))
                .andExpect(status().isBadGateway());
    }

    @Test
    void missingBodyFieldsReturn400() throws Exception {
        mockMvc.perform(post("/api/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"aircraftId":1}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getByFlightIdReturns200And404WhenMissing() throws Exception {
        when(inventoryService.getByFlightId(100L)).thenReturn(inventoryResponse());
        mockMvc.perform(get("/api/inventory/flight/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heldSeats").value(1));

        when(inventoryService.getByFlightId(999L)).thenThrow(new FlightInventoryNotFoundException(999L));
        mockMvc.perform(get("/api/inventory/flight/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void searchReturns200() throws Exception {
        when(inventoryService.search(any(InventorySearchRequest.class)))
                .thenReturn(List.of(inventoryResponse()));

        mockMvc.perform(post("/api/inventory/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"OPEN","minAvailableSeats":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].flightId").value(100));
    }

    @Test
    void holdReturns201() throws Exception {
        when(inventoryFacade.holdSeat(any(HoldSeatRequest.class))).thenReturn(holdResponse());

        mockMvc.perform(post("/api/inventory/hold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightId":100,"seatNumber":"12A","bookingId":42,
                                 "bookingPassengerId":420,"travelClass":"ECONOMY"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.seatNumber").value("12A"))
                .andExpect(jsonPath("$.assignmentMode").value("MANUAL"))
                .andExpect(jsonPath("$.chargedSurcharge").value(12.00));
    }

    @Test
    void holdMissingTravelClassReturns400() throws Exception {
        mockMvc.perform(post("/api/inventory/hold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightId":100,"seatNumber":"12A","bookingId":42,"bookingPassengerId":420}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void autoHoldReturns201WithZeroCharge() throws Exception {
        when(inventoryFacade.autoHoldSeat(eq(100L), any(AutoHoldSeatRequest.class)))
                .thenReturn(autoHoldResponse());

        mockMvc.perform(post("/api/inventory/flights/100/holds/auto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookingId":42,"bookingPassengerId":420,"travelClass":"ECONOMY"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignmentMode").value("AUTO"))
                .andExpect(jsonPath("$.chargedSurcharge").value(0.00));
    }

    @Test
    void heldSeatConflictReturns409() throws Exception {
        when(inventoryFacade.holdSeat(any(HoldSeatRequest.class)))
                .thenThrow(new SeatAlreadyHeldException(100L, "12A"));

        mockMvc.perform(post("/api/inventory/hold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightId":100,"seatNumber":"12A","bookingId":42,
                                 "bookingPassengerId":420,"travelClass":"ECONOMY"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Seat 12A on flight 100 is already held"));
    }

    @Test
    void releaseReturns200() throws Exception {
        when(inventoryFacade.releaseHold(any(ReleaseSeatRequest.class))).thenReturn(holdResponse());

        mockMvc.perform(post("/api/inventory/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightId":100,"seatNumber":"12A","bookingId":42}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void closeAndReopenReturn200() throws Exception {
        when(inventoryService.closeInventory(eq(100L), any())).thenReturn(inventoryResponse());
        mockMvc.perform(patch("/api/inventory/flight/100/close").param("reason", "schedule change"))
                .andExpect(status().isOk());

        when(inventoryService.reopenInventory(eq(100L), any())).thenReturn(inventoryResponse());
        mockMvc.perform(patch("/api/inventory/flight/100/reopen"))
                .andExpect(status().isOk());
    }
}
