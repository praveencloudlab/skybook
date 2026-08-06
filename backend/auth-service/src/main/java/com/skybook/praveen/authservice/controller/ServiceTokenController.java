package com.skybook.praveen.authservice.controller;

import com.skybook.praveen.authservice.dto.GuestTokenRequest;
import com.skybook.praveen.authservice.dto.ServiceTokenRequest;
import com.skybook.praveen.authservice.repository.ServiceClientRepository;
import com.skybook.praveen.authservice.service.JwtService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Issues short-lived {@code ROLE_SERVICE} tokens to authenticated machine
 * callers (SECURITY_HARDENING_MODULE.md §3.3). Reachable only on the
 * client-credential ({@code @Order(1)}) chain - de-routed from the gateway, so
 * it is internal-network only. The caller is already authenticated by its
 * client credential; {@code sub} is derived from that authenticated id, never
 * from the request body, and the requested audience must be on the client's
 * allowlist.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class ServiceTokenController {

    private final ServiceClientRepository serviceClientRepository;
    private final JwtService jwtService;

    @PostMapping("/service-token")
    public ResponseEntity<String> issue(Authentication authentication,
                                        @Valid @RequestBody ServiceTokenRequest request) {

        String clientId = authentication.getName();

        var client = serviceClientRepository.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

        if (!client.mayTarget(request.audience())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "client " + clientId + " may not request tokens for audience " + request.audience());
        }

        return ResponseEntity.ok(jwtService.generateServiceToken(clientId, request.audience()));
    }

    /**
     * Issues a booking-scoped GUEST session token (GUEST_CHECKIN_MODULE.md
     * §3.1) - same internal-only chain, same authenticated-client model, one
     * extra gate: minting a browser-facing session is a bigger privilege than
     * minting a machine token for yourself, so it needs the explicit
     * {@code may_issue_guest_tokens} grant. The caller has already verified
     * reference + surname against its own data; auth-service checks the grant
     * and mints, nothing more.
     */
    @PostMapping("/guest-token")
    public ResponseEntity<String> issueGuestToken(Authentication authentication,
                                                  @Valid @RequestBody GuestTokenRequest request) {

        String clientId = authentication.getName();

        var client = serviceClientRepository.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

        if (!client.isMayIssueGuestTokens()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "client " + clientId + " may not issue guest tokens");
        }

        return ResponseEntity.ok(jwtService.generateGuestToken(request.bookingId()));
    }
}
