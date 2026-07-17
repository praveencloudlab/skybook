package com.skybook.praveen.inventoryservice.controller;

import com.skybook.praveen.inventoryservice.dto.request.AutoHoldSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.CreateFlightInventoryRequest;
import com.skybook.praveen.inventoryservice.dto.request.HoldSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.InventorySearchRequest;
import com.skybook.praveen.inventoryservice.dto.request.ReleaseSeatRequest;
import com.skybook.praveen.inventoryservice.dto.response.CabinAvailabilityResponse;
import com.skybook.praveen.inventoryservice.dto.response.FlightInventoryResponse;
import com.skybook.praveen.inventoryservice.dto.response.InventoryHistoryResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatHoldResponse;
import com.skybook.praveen.inventoryservice.facade.InventoryFacade;
import com.skybook.praveen.inventoryservice.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Orchestrated operations (create/hold/release - flight validation and/or
 * events) go through the facade; plain reads and lifecycle ops call the
 * service directly, same split as BookingController.
 */
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class FlightInventoryController {

    private final InventoryFacade inventoryFacade;
    private final InventoryService inventoryService;

    @PostMapping
    public ResponseEntity<FlightInventoryResponse> createInventory(
            @Valid @RequestBody CreateFlightInventoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventoryFacade.createInventory(request));
    }

    @GetMapping("/flight/{flightId}")
    public ResponseEntity<FlightInventoryResponse> getByFlightId(@PathVariable Long flightId) {
        return ResponseEntity.ok(inventoryService.getByFlightId(flightId));
    }

    @PostMapping("/search")
    public ResponseEntity<List<FlightInventoryResponse>> search(
            @Valid @RequestBody InventorySearchRequest criteria) {
        return ResponseEntity.ok(inventoryService.search(criteria));
    }

    @GetMapping("/flight/{flightId}/history")
    public ResponseEntity<List<InventoryHistoryResponse>> getHistory(@PathVariable Long flightId) {
        return ResponseEntity.ok(inventoryService.getHistory(flightId));
    }

    // Which cabins does this flight sell (§7/§11)? Availability only - fares
    // are assembled solely by booking-service's /quote.
    @GetMapping("/flights/{flightId}/cabins")
    public ResponseEntity<List<CabinAvailabilityResponse>> getCabinAvailability(@PathVariable Long flightId) {
        return ResponseEntity.ok(inventoryService.getCabinAvailability(flightId));
    }

    @PostMapping("/hold")
    public ResponseEntity<SeatHoldResponse> holdSeat(@Valid @RequestBody HoldSeatRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventoryFacade.holdSeat(request));
    }

    // Free auto-assignment (§5.2): no seatNumber - inventory picks a low-demand
    // seat in the passenger's cabin atomically under the flight lock.
    @PostMapping("/flights/{flightId}/holds/auto")
    public ResponseEntity<SeatHoldResponse> autoHoldSeat(
            @PathVariable Long flightId, @Valid @RequestBody AutoHoldSeatRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(inventoryFacade.autoHoldSeat(flightId, request));
    }

    @PostMapping("/release")
    public ResponseEntity<SeatHoldResponse> releaseHold(@Valid @RequestBody ReleaseSeatRequest request) {
        return ResponseEntity.ok(inventoryFacade.releaseHold(request));
    }

    @PatchMapping("/flight/{flightId}/close")
    public ResponseEntity<FlightInventoryResponse> closeInventory(
            @PathVariable Long flightId, @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(inventoryService.closeInventory(flightId, reason));
    }

    @PatchMapping("/flight/{flightId}/reopen")
    public ResponseEntity<FlightInventoryResponse> reopenInventory(
            @PathVariable Long flightId, @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(inventoryService.reopenInventory(flightId, reason));
    }
}
