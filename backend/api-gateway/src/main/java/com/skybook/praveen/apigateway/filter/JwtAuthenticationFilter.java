package com.skybook.praveen.apigateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skybook.praveen.apigateway.security.GatewayJwtValidator;
import com.skybook.praveen.apigateway.security.HeaderAddingRequestWrapper;
import com.skybook.praveen.common.exception.ErrorResponse;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
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
 * The gateway's actual JWT enforcement point (design doc §4) - this is the
 * ONE place in the whole fleet that currently rejects an unauthenticated
 * request to a protected route; every downstream service's SecurityConfig
 * is still permitAll() (see the design doc's second load-bearing finding).
 *
 * Public routes (auth-service's register/login, this gateway's own
 * actuator) bypass validation entirely. Everything else needs a valid
 * "Authorization: Bearer <token>" that GatewayJwtValidator accepts; on
 * success the validated subject is attached as X-Auth-User for downstream
 * services to optionally trust later.
 */
@Slf4j
@Component
@Order(Integer.MIN_VALUE + 10)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTH_USER_HEADER = "X-Auth-User";

    private static final List<PathPattern> PUBLIC_PATHS = List.of(
            new PathPatternParser().parse("/api/auth/register"),
            new PathPatternParser().parse("/api/auth/login"),
            new PathPatternParser().parse("/actuator/**")
    );

    private final GatewayJwtValidator jwtValidator;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(GatewayJwtValidator jwtValidator, ObjectMapper objectMapper) {
        this.jwtValidator = jwtValidator;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        if (isPublic(path)) {
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
            String subject = jwtValidator.validateAndExtractSubject(token);
            HttpServletRequest forwarded = wrapWithAuthUser(request, subject);
            filterChain.doFilter(forwarded, response);
        } catch (JwtException | IllegalArgumentException e) {
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
