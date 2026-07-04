package com.skybook.praveen.inventoryservice.controller;

import com.skybook.praveen.inventoryservice.dto.request.ReleaseSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.ReserveSeatRequest;
import com.skybook.praveen.inventoryservice.dto.response.SeatReservationResponse;
import com.skybook.praveen.inventoryservice.facade.InventoryFacade;
import com.skybook.praveen.inventoryservice.service.SeatReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class SeatReservationController {

    private final InventoryFacade inventoryFacade;
    private final SeatReservationService seatReservationService;

    @PostMapping
    public ResponseEntity<SeatReservationResponse> reserveSeat(@Valid @RequestBody ReserveSeatRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventoryFacade.reserveSeat(request));
    }

    @PostMapping("/cancel")
    public ResponseEntity<SeatReservationResponse> cancelReservation(
            @Valid @RequestBody ReleaseSeatRequest request) {
        return ResponseEntity.ok(inventoryFacade.cancelReservation(request));
    }

    @GetMapping("/booking/{bookingId}")
    public ResponseEntity<List<SeatReservationResponse>> getReservationsByBooking(@PathVariable Long bookingId) {
        return ResponseEntity.ok(seatReservationService.getReservationsByBooking(bookingId));
    }

    @GetMapping("/flight/{flightId}")
    public ResponseEntity<List<SeatReservationResponse>> getReservationsByFlight(@PathVariable Long flightId) {
        return ResponseEntity.ok(seatReservationService.getReservationsByFlight(flightId));
    }
}
