package com.skybook.praveen.apigateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skybook.praveen.apigateway.security.HeaderAddingRequestWrapper;
import com.skybook.praveen.common.exception.ErrorResponse;
import com.skybook.praveen.security.AuthenticatedPrincipal;
import com.skybook.praveen.security.InvalidTokenException;
import com.skybook.praveen.security.JwtTokenValidator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The gateway's JWT enforcement point (SECURITY_HARDENING_MODULE.md §3.2). As
 * of the security-hardening branch it verifies with the shared
 * {@link JwtTokenValidator} - the exact logic every downstream service uses -
 * instead of a gateway-local validator, so the edge and the services can never
 * drift. The gateway's validator is configured with
 * {@code accept-service-tokens=false}, so a machine token can never enter
 * through the public edge (§5).
 *
 * Public routes (auth register/login, this gateway's own actuator) and CORS
 * preflight (OPTIONS) bypass validation. Everything else needs a valid
 * "Authorization: Bearer <token>"; on success the validated subject is attached
 * as X-Auth-User for logging/tracing (never trusted downstream as identity - §3.2).
 */
@Slf4j
@Component
@Order(Integer.MIN_VALUE + 10)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTH_USER_HEADER = "X-Auth-User";

    private static final List<PathPattern> PUBLIC_PATHS = List.of(
            new PathPatternParser().parse("/api/auth/register"),
            new PathPatternParser().parse("/api/auth/login"),
            // Actuator moved to the internal management port (§7); /livez + /readyz
            // are the k8s probe paths re-exposed on this main port.
            new PathPatternParser().parse("/actuator/**"),
            new PathPatternParser().parse("/livez"),
            new PathPatternParser().parse("/readyz")
    );

    private final JwtTokenValidator jwtValidator;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtTokenValidator jwtValidator, ObjectMapper objectMapper) {
        this.jwtValidator = jwtValidator;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        if (HttpMethod.OPTIONS.matches(request.getMethod()) || isPublic(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            reject(request, response, "Missing or malformed Authorization header");
            return;
        }

        String token = authHeader.substring("Bearer ".length());
        try {
            AuthenticatedPrincipal principal = jwtValidator.validate(token);
            HttpServletRequest forwarded = wrapWithAuthUser(request, principal.subject());
            filterChain.doFilter(forwarded, response);
        } catch (InvalidTokenException e) {
            log.warn("JWT rejected for {} {}: {}", request.getMethod(), path, e.getMessage());
            reject(request, response, "Invalid or expired token");
        }
    }

    private boolean isPublic(String path) {
        PathContainer pathContainer = PathContainer.parsePath(path);
        return PUBLIC_PATHS.stream().anyMatch(pattern -> pattern.matches(pathContainer));
    }

    private HttpServletRequest wrapWithAuthUser(HttpServletRequest request, String subject) {
        return new HeaderAddingRequestWrapper(request, AUTH_USER_HEADER, subject);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        var errorBody = new ErrorResponse(
                LocalDateTime.now(), 401, "Unauthorized", message, request.getRequestURI());
        response.getWriter().write(objectMapper.writeValueAsString(errorBody));
    }
}
