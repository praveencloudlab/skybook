package com.skybook.praveen.checkinservice.controller;

import com.skybook.praveen.checkinservice.dto.response.BoardingPassResponse;
import com.skybook.praveen.checkinservice.dto.response.BoardingPassVerifyResponse;
import com.skybook.praveen.checkinservice.service.BoardingPassEmailService;
import com.skybook.praveen.checkinservice.service.BoardingPassService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/boarding-passes")
@RequiredArgsConstructor
public class BoardingPassController {

    private final BoardingPassService boardingPassService;
    private final BoardingPassEmailService boardingPassEmailService;
    private final com.skybook.praveen.checkinservice.security.CheckInAccessGuard accessGuard;

    /** By pass id - a gate/back-office lookup (ADMIN, enforced by the URL rule). */
    @GetMapping("/{id}")
    public ResponseEntity<BoardingPassResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(boardingPassService.getById(id));
    }

    /** The passenger's own pass for a check-in (OWNER, enforced here). */
    @GetMapping("/checkin/{checkInId}")
    public ResponseEntity<BoardingPassResponse> getActiveForCheckIn(@PathVariable Long checkInId) {
        accessGuard.requireOwnerOfCheckIn(checkInId);
        return ResponseEntity.ok(boardingPassService.getActiveForCheckIn(checkInId));
    }

    /** Gate verification (design doc section 6/7) - 422 on any verification failure, not a 200 body. */
    @GetMapping("/verify")
    public ResponseEntity<BoardingPassVerifyResponse> verify(@RequestParam String token) {
        return ResponseEntity.ok(boardingPassService.verify(token));
    }

    public record EmailBoardingPassRequest(
            @NotBlank(message = "Email address is required")
            @Email(message = "Email must be a valid address")
            String email) {
    }

    /**
     * Email the pass to a caller-chosen address (GUEST_CHECKIN_MODULE.md §5) -
     * owner, admin, or guest-of-this-booking alike; an account holder wanting
     * the pass at a second inbox is the same feature. Checked-in only,
     * throttled, audited (all inside the service).
     */
    @PostMapping("/checkin/{checkInId}/email")
    public ResponseEntity<Void> emailToAddress(@PathVariable Long checkInId,
                                               @Valid @RequestBody EmailBoardingPassRequest request) {
        accessGuard.requireOwnerOfCheckIn(checkInId);
        boardingPassEmailService.emailBoardingPass(checkInId, request.email());
        return ResponseEntity.accepted().build();
    }
}
