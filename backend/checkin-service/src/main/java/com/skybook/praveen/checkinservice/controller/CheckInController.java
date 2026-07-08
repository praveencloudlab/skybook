package com.skybook.praveen.checkinservice.controller;

import com.skybook.praveen.checkinservice.dto.request.CreateCheckInRequest;
import com.skybook.praveen.checkinservice.dto.request.GateAssignmentRequest;
import com.skybook.praveen.checkinservice.dto.request.SeatChangeRequest;
import com.skybook.praveen.checkinservice.dto.response.CheckInResponse;
import com.skybook.praveen.checkinservice.facade.CheckInFacade;
import com.skybook.praveen.checkinservice.service.CheckInService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Orchestrated operations (checkin/board/seat - external calls + events) go
 * through the facade; creation, reads, and simple field updates call the
 * service directly - same split as the sibling services' controllers.
 */
@RestController
@RequestMapping("/api/checkins")
@RequiredArgsConstructor
public class CheckInController {

    private final CheckInService checkInService;
    private final CheckInFacade checkInFacade;

    /** Manual/direct creation - the normal path is the BookingEvent CONFIRMED consumer. */
    @PostMapping
    public ResponseEntity<CheckInResponse> createCheckIn(@Valid @RequestBody CreateCheckInRequest request) {
        CheckInResponse created = checkInService.createCheckIn(request, "USER", "API", request.bookingReference());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CheckInResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(checkInService.getById(id));
    }

    @GetMapping("/booking/{bookingId}")
    public ResponseEntity<List<CheckInResponse>> getByBookingId(@PathVariable Long bookingId) {
        return ResponseEntity.ok(checkInService.getByBookingId(bookingId));
    }

    @GetMapping("/flight/{flightId}")
    public ResponseEntity<List<CheckInResponse>> getByFlightId(@PathVariable Long flightId) {
        return ResponseEntity.ok(checkInService.getByFlightId(flightId));
    }

    @PatchMapping("/{id}/open")
    public ResponseEntity<CheckInResponse> openWindow(@PathVariable Long id) {
        return ResponseEntity.ok(checkInService.openWindow(id));
    }

    @PatchMapping("/{id}/checkin")
    public ResponseEntity<CheckInResponse> checkIn(@PathVariable Long id) {
        return ResponseEntity.ok(checkInFacade.checkIn(id));
    }

    @PatchMapping("/{id}/board")
    public ResponseEntity<CheckInResponse> board(@PathVariable Long id) {
        return ResponseEntity.ok(checkInFacade.board(id));
    }

    @PatchMapping("/{id}/seat")
    public ResponseEntity<CheckInResponse> changeSeat(
            @PathVariable Long id, @Valid @RequestBody SeatChangeRequest request) {
        return ResponseEntity.ok(checkInFacade.changeSeat(id, request.newSeatNumber()));
    }

    @PatchMapping("/{id}/gate")
    public ResponseEntity<CheckInResponse> assignGate(
            @PathVariable Long id, @Valid @RequestBody GateAssignmentRequest request) {
        return ResponseEntity.ok(checkInService.assignGate(id, request.gate()));
    }
}
