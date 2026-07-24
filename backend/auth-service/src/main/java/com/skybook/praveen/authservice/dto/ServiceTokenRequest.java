package com.skybook.praveen.authservice.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /api/auth/service-token} (SECURITY_HARDENING_MODULE.md
 * §3.3): only the requested audience. Identity is the authenticated client
 * credential - never taken from the body.
 */
public record ServiceTokenRequest(
        @NotBlank(message = "audience is required")
        String audience
) {
}
