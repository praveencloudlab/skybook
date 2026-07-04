package com.skybook.praveen.inventoryservice.controller;

import com.skybook.praveen.inventoryservice.dto.request.CreateAircraftSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.CreateSeatMapRequest;
import com.skybook.praveen.inventoryservice.dto.response.AircraftSeatResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatMapResponse;
import com.skybook.praveen.inventoryservice.enums.AircraftSeatStatus;
import com.skybook.praveen.inventoryservice.service.AircraftSeatService;
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

@RestController
@RequestMapping("/api/aircraft/{aircraftId}")
@RequiredArgsConstructor
public class AircraftSeatController {

    private final AircraftSeatService aircraftSeatService;

    @PostMapping("/seats")
    public ResponseEntity<AircraftSeatResponse> addSeat(
            @PathVariable Long aircraftId, @Valid @RequestBody CreateAircraftSeatRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(aircraftSeatService.addSeat(aircraftId, request));
    }

    @PostMapping("/seat-map")
    public ResponseEntity<List<AircraftSeatResponse>> createSeatMap(
            @PathVariable Long aircraftId, @Valid @RequestBody CreateSeatMapRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(aircraftSeatService.createSeatMap(aircraftId, request));
    }

    @GetMapping("/seat-map")
    public ResponseEntity<SeatMapResponse> getSeatMap(@PathVariable Long aircraftId) {
        return ResponseEntity.ok(aircraftSeatService.getSeatMap(aircraftId));
    }

    @GetMapping("/seats/status/{status}")
    public ResponseEntity<List<AircraftSeatResponse>> getSeatsByStatus(
            @PathVariable Long aircraftId, @PathVariable AircraftSeatStatus status) {
        return ResponseEntity.ok(aircraftSeatService.getSeatsByStatus(aircraftId, status));
    }

    @PatchMapping("/seats/{seatNumber}/status")
    public ResponseEntity<AircraftSeatResponse> updateSeatStatus(
            @PathVariable Long aircraftId, @PathVariable String seatNumber,
            @RequestParam AircraftSeatStatus status) {
        return ResponseEntity.ok(aircraftSeatService.updateSeatStatus(aircraftId, seatNumber, status));
    }
}
