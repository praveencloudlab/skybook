package com.skybook.praveen.inventoryservice.facade;

import com.skybook.praveen.inventoryservice.client.FlightDetails;
import com.skybook.praveen.inventoryservice.client.FlightServiceClient;
import com.skybook.praveen.inventoryservice.dto.request.AutoHoldSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.CreateFlightInventoryRequest;
import com.skybook.praveen.inventoryservice.dto.request.HoldSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.ReleaseSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.ReserveSeatRequest;
import com.skybook.praveen.inventoryservice.dto.response.FlightInventoryResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatHoldResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatReservationResponse;
import com.skybook.praveen.inventoryservice.enums.InventoryStatus;
import com.skybook.praveen.inventoryservice.enums.SeatAssignmentMode;
import com.skybook.praveen.inventoryservice.enums.SeatHoldStatus;
import com.skybook.praveen.inventoryservice.enums.SeatReservationStatus;
import com.skybook.praveen.inventoryservice.enums.SeatType;

import java.math.BigDecimal;
import com.skybook.praveen.inventoryservice.exception.FlightNotFoundForInventoryException;
import com.skybook.praveen.inventoryservice.producer.InventoryEventProducer;
import com.skybook.praveen.inventoryservice.service.InventoryService;
import com.skybook.praveen.inventoryservice.service.SeatReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryFacadeTest {

    @Mock
    private FlightServiceClient flightServiceClient;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private SeatReservationService seatReservationService;
    @Mock
    private InventoryEventProducer inventoryEventProducer;

    private InventoryFacade facade;

    private final LocalDateTime now = LocalDateTime.now();

    @BeforeEach
    void setUp() {
        facade = new InventoryFacade(
                flightServiceClient, inventoryService, seatReservationService, inventoryEventProducer);
    }

    private FlightDetails flight(String status) {
        return new FlightDetails(1L, "SB101", "HYD", "LHR", now.plusDays(7), now.plusDays(7).plusHours(10), status);
    }

    private FlightInventoryResponse inventoryResponse() {
        return new FlightInventoryResponse(10L, 1L, 1L, "VT-SKB", InventoryStatus.OPEN,
                3, 3, 0, 0, 0, 0L, now, now);
    }

    private SeatHoldResponse holdResponse() {
        return new SeatHoldResponse(5L, 1L, 2L, "12A", 42L, 420L, SeatAssignmentMode.MANUAL,
                new BigDecimal("12.00"), new BigDecimal("12.00"),
                SeatHoldStatus.ACTIVE, now, now.plusMinutes(15));
    }

    private SeatReservationResponse reservationResponse() {
        return new SeatReservationResponse(9L, 1L, 2L, "12A", 42L, null, 5L,
                SeatReservationStatus.RESERVED, now, null);
    }

    // ---------------------------------------------------------------
    // createInventory - flight validation
    // ---------------------------------------------------------------

    @Test
    void createValidatesFlightThenDelegatesThenPublishes() {
        CreateFlightInventoryRequest request = new CreateFlightInventoryRequest(1L, 1L, 0);
        when(flightServiceClient.getFlight(1L)).thenReturn(flight("SCHEDULED"));
        when(inventoryService.createInventory(request)).thenReturn(inventoryResponse());

        FlightInventoryResponse response = facade.createInventory(request);

        assertThat(response).isEqualTo(inventoryResponse());
        verify(inventoryEventProducer).publishInventoryCreated(inventoryResponse());
    }

    @Test
    void missingFlightStopsBeforeTheService() {
        CreateFlightInventoryRequest request = new CreateFlightInventoryRequest(1L, 1L, 0);
        when(flightServiceClient.getFlight(1L)).thenThrow(new FlightNotFoundForInventoryException(1L));

        assertThatThrownBy(() -> facade.createInventory(request))
                .isInstanceOf(FlightNotFoundForInventoryException.class);

        verifyNoInteractions(inventoryService, inventoryEventProducer);
    }

    @Test
    void cancelledFlightIsRejectedBeforeTheService() {
        CreateFlightInventoryRequest request = new CreateFlightInventoryRequest(1L, 1L, 0);
        when(flightServiceClient.getFlight(1L)).thenReturn(flight("CANCELLED"));

        assertThatThrownBy(() -> facade.createInventory(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cancelled");

        verifyNoInteractions(inventoryService, inventoryEventProducer);
    }

    // ---------------------------------------------------------------
    // Seat operations - delegate + publish
    // ---------------------------------------------------------------

    @Test
    void holdDelegatesAndPublishesSeatHeld() {
        HoldSeatRequest request = new HoldSeatRequest(1L, "12A", 42L, 420L, SeatType.ECONOMY);
        when(inventoryService.holdSeat(request)).thenReturn(holdResponse());

        assertThat(facade.holdSeat(request)).isEqualTo(holdResponse());

        verify(inventoryEventProducer).publishSeatHeld(holdResponse());
        verifyNoInteractions(flightServiceClient); // holds never re-validate the flight
    }

    @Test
    void autoHoldDelegatesAndPublishesSeatHeld() {
        AutoHoldSeatRequest request = new AutoHoldSeatRequest(42L, 420L, SeatType.ECONOMY);
        when(inventoryService.autoHoldSeat(1L, request)).thenReturn(holdResponse());

        assertThat(facade.autoHoldSeat(1L, request)).isEqualTo(holdResponse());

        verify(inventoryEventProducer).publishSeatHeld(holdResponse());
        verifyNoInteractions(flightServiceClient);
    }

    @Test
    void failedHoldPublishesNothing() {
        HoldSeatRequest request = new HoldSeatRequest(1L, "12A", 42L, 420L, SeatType.ECONOMY);
        when(inventoryService.holdSeat(request)).thenThrow(new IllegalStateException("CLOSED"));

        assertThatThrownBy(() -> facade.holdSeat(request)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(inventoryEventProducer);
    }

    @Test
    void releaseDelegatesAndPublishesSeatReleased() {
        ReleaseSeatRequest request = new ReleaseSeatRequest(1L, "12A", 42L, null);
        when(inventoryService.releaseHold(request)).thenReturn(holdResponse());

        facade.releaseHold(request);

        verify(inventoryEventProducer).publishSeatReleased(holdResponse());
    }

    @Test
    void reserveDelegatesAndPublishesSeatReserved() {
        ReserveSeatRequest request = new ReserveSeatRequest(1L, "12A", 42L, null, 5L);
        when(seatReservationService.reserveSeat(request)).thenReturn(reservationResponse());

        facade.reserveSeat(request);

        verify(inventoryEventProducer).publishSeatReserved(reservationResponse());
        verify(inventoryService, never()).holdSeat(any());
    }

    @Test
    void cancelDelegatesAndPublishesReservationCancelled() {
        ReleaseSeatRequest request = new ReleaseSeatRequest(1L, "12A", 42L, "changed plans");
        when(seatReservationService.cancelReservation(request)).thenReturn(reservationResponse());

        facade.cancelReservation(request);

        verify(inventoryEventProducer).publishReservationCancelled(reservationResponse());
    }
}
