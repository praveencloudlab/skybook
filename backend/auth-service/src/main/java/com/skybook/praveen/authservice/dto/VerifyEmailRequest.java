package com.skybook.praveen.authservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Redeem the emailed verification code. The account is identified by email,
 * not by session - the caller cannot sign in yet, that is the whole point.
 */
public record VerifyEmailRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        String email,

        @NotBlank(message = "Verification code is required")
        @Pattern(regexp = "\\d{6}", message = "Verification code is 6 digits")
        String otp

) {
}
