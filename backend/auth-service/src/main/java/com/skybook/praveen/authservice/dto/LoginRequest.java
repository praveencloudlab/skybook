package com.skybook.praveen.authservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Login payload (SECURITY_HARDENING_MODULE.md §6). Password is {@code @NotBlank}
 * only - the registration complexity policy is deliberately NOT applied here, so
 * accounts created under an older policy can still authenticate.
 */
public record LoginRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        String email,

        @NotBlank(message = "Password is required")
        String password

) {
}
