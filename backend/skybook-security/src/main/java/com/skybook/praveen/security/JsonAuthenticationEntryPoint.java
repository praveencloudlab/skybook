package com.skybook.praveen.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skybook.praveen.common.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * 401 responses in the fleet {@link ErrorResponse} shape (rather than
 * Spring's default HTML), so an unauthenticated call to a downstream service
 * looks the same as the gateway's rejection. Body is deliberately generic - no
 * token internals leak.
 */
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse body = new ErrorResponse(
                LocalDateTime.now(), 401, "Unauthorized",
                "Authentication required", request.getRequestURI());
        objectMapper.writeValue(response.getWriter(), body);
    }
}
