package com.skybook.praveen.authservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Change password while signed in (FRONTEND_MODULE.md Module 14). The current
 * password must be presented and verified (so a stolen session can't silently
 * change it), and the new one must clear the same complexity policy registration
 * does - mirrors {@link RegisterRequest} / {@link ResetPasswordRequest}.
 */
public record ChangePasswordRequest(

        @NotBlank(message = "Current password is required")
        String currentPassword,

        @NotBlank(message = "New password is required")
        @Size(min = 12, message = "Password must be at least 12 characters")
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).+$",
                message = "Password must contain an upper- and lower-case letter, a digit, and a symbol"
        )
        String newPassword

) {
}
