package com.skybook.praveen.inventoryservice.controller;

import com.skybook.praveen.inventoryservice.dto.request.CreateAircraftRequest;
import com.skybook.praveen.inventoryservice.dto.response.AircraftResponse;
import com.skybook.praveen.inventoryservice.enums.AircraftStatus;
import com.skybook.praveen.inventoryservice.service.AircraftService;
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
@RequestMapping("/api/aircraft")
@RequiredArgsConstructor
public class AircraftController {

    private final AircraftService aircraftService;

    @PostMapping
    public ResponseEntity<AircraftResponse> createAircraft(@Valid @RequestBody CreateAircraftRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(aircraftService.createAircraft(request));
    }

    @GetMapping
    public ResponseEntity<List<AircraftResponse>> getAllAircraft() {
        return ResponseEntity.ok(aircraftService.getAllAircraft());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AircraftResponse> getAircraftById(@PathVariable Long id) {
        return ResponseEntity.ok(aircraftService.getAircraftById(id));
    }

    @GetMapping("/registration/{registrationNumber}")
    public ResponseEntity<AircraftResponse> getAircraftByRegistrationNumber(
            @PathVariable String registrationNumber) {
        return ResponseEntity.ok(aircraftService.getAircraftByRegistrationNumber(registrationNumber));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<AircraftResponse>> getAircraftByStatus(@PathVariable AircraftStatus status) {
        return ResponseEntity.ok(aircraftService.getAircraftByStatus(status));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<AircraftResponse> updateAircraftStatus(
            @PathVariable Long id, @RequestParam AircraftStatus status) {
        return ResponseEntity.ok(aircraftService.updateAircraftStatus(id, status));
    }
}
