package com.skybook.praveen.checkinservice.controller;

import com.skybook.praveen.checkinservice.dto.request.CreateBaggageRequest;
import com.skybook.praveen.checkinservice.dto.response.BaggageResponse;
import com.skybook.praveen.checkinservice.service.BaggageService;
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
@RequestMapping("/api/baggage")
@RequiredArgsConstructor
public class BaggageController {

    private final BaggageService baggageService;
    private final com.skybook.praveen.checkinservice.security.CheckInAccessGuard accessGuard;

    @PostMapping
    public ResponseEntity<BaggageResponse> addBaggage(@Valid @RequestBody CreateBaggageRequest request) {
        accessGuard.requireOwnerOfCheckIn(request.checkInId());
        return ResponseEntity.status(HttpStatus.CREATED).body(baggageService.addBaggage(request));
    }

    @GetMapping("/checkin/{checkInId}")
    public ResponseEntity<List<BaggageResponse>> getByCheckInId(@PathVariable Long checkInId) {
        accessGuard.requireOwnerOfCheckIn(checkInId);
        return ResponseEntity.ok(baggageService.getByCheckInId(checkInId));
    }
}
