package com.skybook.praveen.bookingservice.controller;

import com.skybook.praveen.bookingservice.service.GuestSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
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
public class GuestSessionController {

    public static final String COOKIE_NAME = "__Host-skybook_guest";

    /** Matches the token's own 30-minute life (GUEST_CHECKIN_MODULE.md §3.1). */
    private static final Duration COOKIE_TTL = Duration.ofMinutes(30);

    private final GuestSessionService guestSessionService;

    /**
     * Secure-by-default, exactly like the account session cookie - and the
     * {@code __Host-} prefix REQUIRES it, which is the point.
     *
     * <p>Configurable only because of a real gap the e2e suite exposed:
     * browsers treat {@code http://localhost} as a secure context and happily
     * accept the cookie there, but non-browser HTTP clients (the
     * certification suite's, any script) refuse to send a Secure cookie over
     * plain HTTP - so the cookie half of this feature would have been
     * untestable end to end. Set false ONLY in the e2e overlay, where the
     * fleet is driven over HTTP by a client and no browser is involved.
     */
    private final boolean secure;

    public GuestSessionController(GuestSessionService guestSessionService,
                                  @Value("${skybook.guest.cookie-secure:true}") boolean secure) {
        this.guestSessionService = guestSessionService;
        this.secure = secure;
    }

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
                .secure(secure)          // __Host- requires it, browser-enforced
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
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expired.toString())
                .build();
    }
}
