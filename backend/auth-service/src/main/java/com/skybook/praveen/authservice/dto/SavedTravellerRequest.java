package com.skybook.praveen.authservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Create or update a saved traveller (FRONTEND_MODULE.md Module 14). Name is
 * required; passport details are optional (a traveller may be saved before their
 * document is to hand). Nationality is a 3-letter code when present.
 */
public record SavedTravellerRequest(
        String title,
        @NotBlank(message = "First name is required")
        String firstName,
        @NotBlank(message = "Last name is required")
        String lastName,
        LocalDate dateOfBirth,
        @Size(min = 3, max = 3, message = "Nationality must be a 3-letter code")
        String nationality,
        String passportNumber,
        LocalDate passportExpiry
) {
}
