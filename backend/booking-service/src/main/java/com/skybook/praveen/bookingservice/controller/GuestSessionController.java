package com.skybook.praveen.bookingservice.controller;

import com.skybook.praveen.bookingservice.service.GuestSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * The guest check-in front door (GUEST_CHECKIN_MODULE.md §3): reference +
 * surname in, a booking-scoped session cookie out.
 *
 * <p>The cookie is <b>{@code __Host-skybook_guest}</b>, deliberately NOT the
 * account session cookie: reusing {@code skybook_session} would overwrite a
 * signed-in user's session - including the agency's own - the moment anyone
 * looked up a booking as a guest. The {@code __Host-} prefix makes Secure +
 * Path=/ + no-Domain browser-enforced (browsers treat localhost as a secure
 * context, so dev works); the gateway prefers this cookie only on the
 * explicit guest-capable path list, so account sessions win everywhere else.
 *
 * <p>The response body carries the numeric {@code bookingId} - from here the
 * guest UI is id-based, and the reference (half the credential) never appears
 * in a URL or an access log again.
 */
@RestController
@RequestMapping("/api/bookings/guest-session")
@RequiredArgsConstructor
public class GuestSessionController {

    public static final String COOKIE_NAME = "__Host-skybook_guest";

    /** Matches the token's own 30-minute life (GUEST_CHECKIN_MODULE.md §3.1). */
    private static final Duration COOKIE_TTL = Duration.ofMinutes(30);

    private final GuestSessionService guestSessionService;

    public record GuestLookupRequest(
            @NotBlank(message = "Booking reference is required")
            String bookingReference,
            @NotBlank(message = "Last name is required")
            String lastName) {
    }

    @PostMapping
    public ResponseEntity<Map<String, Long>> issue(@Valid @RequestBody GuestLookupRequest request) {
        GuestSessionService.GuestSession session =
                guestSessionService.issue(request.bookingReference(), request.lastName());

        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, session.token())
                .httpOnly(true)
                .secure(true)          // __Host- requires it, browser-enforced
                .sameSite("Lax")
                .path("/")             // __Host- requires exactly "/"
                .maxAge(COOKIE_TTL)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(Map.of("bookingId", session.bookingId()));
    }

    /**
     * Explicit session end (§3.2) - shared computers should not depend on
     * tab-closing. Public like the issue leg: expiring a cookie must work
     * even when the token inside it has already lapsed.
     */
    @DeleteMapping
    public ResponseEntity<Void> end() {
        ResponseCookie expired = ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expired.toString())
                .build();
    }
}
