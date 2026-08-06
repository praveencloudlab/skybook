package com.skybook.praveen.apigateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skybook.praveen.security.AuthenticatedPrincipal;
import com.skybook.praveen.security.InvalidTokenException;
import com.skybook.praveen.security.JwtTokenValidator;
import com.skybook.praveen.security.TokenType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private final JwtTokenValidator jwtValidator = mock(JwtTokenValidator.class);
    private final JwtAuthenticationFilter filter =
            new JwtAuthenticationFilter(jwtValidator, new ObjectMapper().findAndRegisterModules());

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private boolean chainInvoked;
    private ServletRequest forwardedRequest;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chainInvoked = false;
        forwardedRequest = null;
    }

    private FilterChain chain() {
        return (req, res) -> {
            chainInvoked = true;
            forwardedRequest = req;
        };
    }

    private static AuthenticatedPrincipal userPrincipal(String subject) {
        return new AuthenticatedPrincipal(subject, TokenType.USER, List.of("ROLE_USER"), "skybook-api");
    }

    @Test
    void optionsRequestBypassesAuthEvenForAProtectedPath() throws Exception {
        request.setMethod("OPTIONS");
        request.setRequestURI("/api/flights/123");

        filter.doFilter(request, response, chain());

        assertThat(chainInvoked).isTrue();
        verifyNoInteractions(jwtValidator);
    }

    @Test
    void publicLoginPathBypassesAuth() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/api/auth/login");

        filter.doFilter(request, response, chain());

        assertThat(chainInvoked).isTrue();
        verifyNoInteractions(jwtValidator);
    }

    @Test
    void actuatorPathIsPublic() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/actuator/health");

        filter.doFilter(request, response, chain());

        assertThat(chainInvoked).isTrue();
    }

    @Test
    void missingAuthorizationHeaderReturns401() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api/bookings/123");

        filter.doFilter(request, response, chain());

        assertThat(chainInvoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Missing or malformed Authorization header");
    }

    @Test
    void malformedAuthorizationHeaderReturns401() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api/bookings/123");
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

        filter.doFilter(request, response, chain());

        assertThat(chainInvoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void invalidTokenReturns401() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api/bookings/123");
        request.addHeader("Authorization", "Bearer bad-token");
        when(jwtValidator.validate("bad-token")).thenThrow(new InvalidTokenException("expired"));

        filter.doFilter(request, response, chain());

        assertThat(chainInvoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Invalid or expired token");
    }

    @Test
    void validTokenForwardsTheRequestWithAuthUserHeaderAttached() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api/bookings/123");
        request.addHeader("Authorization", "Bearer good-token");
        when(jwtValidator.validate("good-token")).thenReturn(userPrincipal("traveler@skybook.com"));

        filter.doFilter(request, response, chain());

        assertThat(chainInvoked).isTrue();
        assertThat(((HttpServletRequest) forwardedRequest).getHeader(JwtAuthenticationFilter.AUTH_USER_HEADER))
                .isEqualTo("traveler@skybook.com");
    }

    // -------------------------------------------------- guest sessions
    // GUEST_CHECKIN_MODULE.md §3.2: two ambient credentials can coexist, and
    // precedence is path-decided by the explicit guest-capable list.

    private static AuthenticatedPrincipal guestPrincipal(long bookingId) {
        return new AuthenticatedPrincipal("guest:" + bookingId, TokenType.GUEST,
                List.of("ROLE_GUEST"), "skybook-api", bookingId);
    }

    private void bothCookies() {
        request.setCookies(
                new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.SESSION_COOKIE, "session-token"),
                new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.GUEST_COOKIE, "guest-token"));
    }

    @Test
    void theGuestCookieWinsInsideTheGuestCapableSurface() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api/checkins/booking/41");
        bothCookies();
        when(jwtValidator.validate("guest-token")).thenReturn(guestPrincipal(41L));

        filter.doFilter(request, response, chain());

        assertThat(chainInvoked).isTrue();
        assertThat(((HttpServletRequest) forwardedRequest).getHeader("Authorization"))
                .isEqualTo("Bearer guest-token");
    }

    @Test
    void theAccountSessionWinsEverywhereElse() throws Exception {
        // A signed-in user who also looked up a booking as a guest must NOT
        // be silently downgraded on their own trips page.
        request.setMethod("GET");
        request.setRequestURI("/api/bookings/mine");
        bothCookies();
        when(jwtValidator.validate("session-token")).thenReturn(userPrincipal("traveler@skybook.com"));

        filter.doFilter(request, response, chain());

        assertThat(chainInvoked).isTrue();
        assertThat(((HttpServletRequest) forwardedRequest).getHeader("Authorization"))
                .isEqualTo("Bearer session-token");
    }

    @Test
    void aGuestCookieAloneCarriesTheGuestSurface() throws Exception {
        request.setMethod("PATCH");
        request.setRequestURI("/api/checkins/7/checkin");
        request.setCookies(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.GUEST_COOKIE, "guest-token"));
        when(jwtValidator.validate("guest-token")).thenReturn(guestPrincipal(41L));

        filter.doFilter(request, response, chain());

        assertThat(chainInvoked).isTrue();
    }

    @Test
    void theGuestSessionEndpointIsPublic() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/api/bookings/guest-session");

        filter.doFilter(request, response, chain());

        assertThat(chainInvoked).isTrue();
        verifyNoInteractions(jwtValidator);
    }

    @Test
    void inboundIdentityHeadersAreStrippedOnPublicPaths() throws Exception {
        // The gateway is X-Auth-User's only legitimate author
        // (GUEST_CHECKIN_MODULE.md §2.9): a spoofed inbound copy must not
        // reach downstream logs even where no validation runs.
        request.setMethod("GET");
        request.setRequestURI("/api/flights/123");
        request.addHeader(JwtAuthenticationFilter.AUTH_USER_HEADER, "spoofed@evil.com");

        filter.doFilter(request, response, chain());

        assertThat(chainInvoked).isTrue();
        HttpServletRequest forwarded = (HttpServletRequest) forwardedRequest;
        assertThat(forwarded.getHeader(JwtAuthenticationFilter.AUTH_USER_HEADER)).isNull();
        assertThat(Collections.list(forwarded.getHeaderNames()))
                .noneMatch(JwtAuthenticationFilter.AUTH_USER_HEADER::equalsIgnoreCase);
    }
}
