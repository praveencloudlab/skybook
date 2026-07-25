package com.skybook.praveen.authservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * "Forgot password" payload. Only an email is needed to start a reset. The
 * response is identical whether or not an account exists (no enumeration, in
 * keeping with {@link LoginRequest}); this DTO just has to be a well-formed
 * address.
 */
public record ForgotPasswordRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        String email

) {
}
