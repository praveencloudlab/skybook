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
        request.setRequestURI("/api/flights/123");

        filter.doFilter(request, response, chain());

        assertThat(chainInvoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Missing or malformed Authorization header");
    }

    @Test
    void malformedAuthorizationHeaderReturns401() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api/flights/123");
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

        filter.doFilter(request, response, chain());

        assertThat(chainInvoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void invalidTokenReturns401() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api/flights/123");
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
        request.setRequestURI("/api/flights/123");
        request.addHeader("Authorization", "Bearer good-token");
        when(jwtValidator.validate("good-token")).thenReturn(userPrincipal("traveler@skybook.com"));

        filter.doFilter(request, response, chain());

        assertThat(chainInvoked).isTrue();
        assertThat(((HttpServletRequest) forwardedRequest).getHeader(JwtAuthenticationFilter.AUTH_USER_HEADER))
                .isEqualTo("traveler@skybook.com");
    }
}
