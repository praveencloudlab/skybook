package com.skybook.praveen.apigateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skybook.praveen.apigateway.security.FixedWindowRateLimiter;
import com.skybook.praveen.common.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Per-client-IP throttling (design doc §6) - runs before JWT validation so
 * it also protects auth-service's public login/register endpoints from
 * being hammered, not just already-authenticated traffic.
 */
@Component
@Order(Integer.MIN_VALUE + 5)
public class RateLimitFilter extends OncePerRequestFilter {

    private final FixedWindowRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(FixedWindowRateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String clientKey = request.getRemoteAddr();

        if (!rateLimiter.tryAcquire(clientKey)) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            var errorBody = new ErrorResponse(
                    LocalDateTime.now(), 429, "Too Many Requests",
                    "Rate limit exceeded - try again later", request.getRequestURI());
            response.getWriter().write(objectMapper.writeValueAsString(errorBody));
            return;
        }

        filterChain.doFilter(request, response);
    }
}
