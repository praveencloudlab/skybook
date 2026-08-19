package com.skybook.praveen.authservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Ask for a fresh verification code. Answered identically whether or not the
 * address has an unverified account (no enumeration, same doctrine as
 * {@link ForgotPasswordRequest}).
 */
public record ResendVerificationRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        String email

) {
}
