package com.skybook.praveen.authservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * "Reset password" payload: the token from the emailed link plus the new
 * password. The complexity policy mirrors {@link RegisterRequest} exactly - a
 * reset sets a brand-new password, so it must clear the same bar registration
 * does (unlike {@link LoginRequest}, which deliberately does not).
 */
public record ResetPasswordRequest(

        @NotBlank(message = "Reset token is required")
        String token,

        @NotBlank(message = "Password is required")
        @Size(min = 12, message = "Password must be at least 12 characters")
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).+$",
                message = "Password must contain an upper- and lower-case letter, a digit, and a symbol"
        )
        String password

) {
}
