package com.skybook.praveen.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skybook.praveen.common.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * 403 responses in the fleet {@link ErrorResponse} shape - an authenticated
 * caller lacking the required role/ownership (§4) gets a consistent JSON body
 * rather than Spring's default HTML.
 */
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public JsonAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse body = new ErrorResponse(
                LocalDateTime.now(), 403, "Forbidden",
                "You do not have permission to perform this action", request.getRequestURI());
        objectMapper.writeValue(response.getWriter(), body);
    }
}
