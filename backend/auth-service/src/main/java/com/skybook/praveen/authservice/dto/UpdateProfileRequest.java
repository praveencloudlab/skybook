package com.skybook.praveen.authservice.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Editable profile fields (FRONTEND_MODULE.md Module 14). Email and role are NOT
 * here - identity is not user-editable. Everything is optional so the passenger
 * can fill in as much or as little as they like; nationality is a 3-letter code
 * when present.
 */
public record UpdateProfileRequest(
        String fullName,
        String phone,
        LocalDate dateOfBirth,
        @Size(min = 3, max = 3, message = "Nationality must be a 3-letter code")
        String nationality,
        String passportNumber,
        LocalDate passportExpiry,
        String emergencyContactName,
        String emergencyContactPhone,

        @jakarta.validation.constraints.Size(max = 5)
        String preferredLanguage,

        @jakarta.validation.constraints.Size(min = 3, max = 3)
        String preferredCurrency
) {
}
