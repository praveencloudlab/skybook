package com.skybook.praveen.authservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registration payload (SECURITY_HARDENING_MODULE.md §6). Complexity is enforced
 * here, at *registration* only - applying it at login would lock out accounts
 * created under the old policy (see {@link LoginRequest}).
 */
public record RegisterRequest(

        @NotBlank(message = "Full name is required")
        String fullName,

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 12, message = "Password must be at least 12 characters")
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).+$",
                message = "Password must contain an upper- and lower-case letter, a digit, and a symbol"
        )
        String password

) {
}
