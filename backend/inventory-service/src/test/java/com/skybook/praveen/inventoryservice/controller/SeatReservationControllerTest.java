package com.skybook.praveen.inventoryservice.controller;

import com.skybook.praveen.inventoryservice.config.WebSliceSecurityConfig;
import com.skybook.praveen.inventoryservice.dto.request.ReleaseSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.ReserveSeatRequest;
import com.skybook.praveen.inventoryservice.dto.response.SeatReservationResponse;
import com.skybook.praveen.inventoryservice.enums.SeatReservationStatus;
import com.skybook.praveen.inventoryservice.exception.SeatAlreadyReservedException;
import com.skybook.praveen.inventoryservice.exception.SeatHoldExpiredException;
import com.skybook.praveen.inventoryservice.facade.InventoryFacade;
import com.skybook.praveen.inventoryservice.service.SeatReservationService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SeatReservationController.class)
@Import(WebSliceSecurityConfig.class)
class SeatReservationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InventoryFacade inventoryFacade;

    @MockitoBean
    private SeatReservationService seatReservationService;

    private SeatReservationResponse reservationResponse(SeatReservationStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return new SeatReservationResponse(9L, 100L, 2L, "12A", 42L, 200L, 5L, status, now,
                status == SeatReservationStatus.CANCELLED ? now : null);
    }

    @Test
    void reserveReturns201() throws Exception {
        when(inventoryFacade.reserveSeat(any(ReserveSeatRequest.class)))
                .thenReturn(reservationResponse(SeatReservationStatus.RESERVED));

        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightId":100,"seatNumber":"12A","bookingId":42,"bookingPassengerId":200,"holdId":5}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.originatingHoldId").value(5));
    }

    @Test
    void expiredHoldReturns410() throws Exception {
        when(inventoryFacade.reserveSeat(any(ReserveSeatRequest.class)))
                .thenThrow(new SeatHoldExpiredException(5L));

        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightId":100,"seatNumber":"12A","bookingId":42,"holdId":5}
                                """))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value("Seat hold 5 has expired"));
    }

    @Test
    void alreadyReservedReturns409() throws Exception {
        when(inventoryFacade.reserveSeat(any(ReserveSeatRequest.class)))
                .thenThrow(new SeatAlreadyReservedException(100L, "12A"));

        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightId":100,"seatNumber":"12A","bookingId":42}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void missingBookingIdReturns400() throws Exception {
        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightId":100,"seatNumber":"12A"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancelReturns200WithCancelledStatus() throws Exception {
        when(inventoryFacade.cancelReservation(any(ReleaseSeatRequest.class)))
                .thenReturn(reservationResponse(SeatReservationStatus.CANCELLED));

        mockMvc.perform(post("/api/reservations/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightId":100,"seatNumber":"12A","bookingId":42,"reason":"booking cancelled"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").exists());
    }

    @Test
    void getByBookingAndByFlightReturn200() throws Exception {
        when(seatReservationService.getReservationsByBooking(42L))
                .thenReturn(List.of(reservationResponse(SeatReservationStatus.RESERVED)));
        mockMvc.perform(get("/api/reservations/booking/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bookingId").value(42));

        when(seatReservationService.getReservationsByFlight(100L))
                .thenReturn(List.of(reservationResponse(SeatReservationStatus.RESERVED)));
        mockMvc.perform(get("/api/reservations/flight/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].flightId").value(100));
    }
}
