package com.skybook.praveen.authservice.dto;

import java.time.LocalDate;

/**
 * The passenger's profile (FRONTEND_MODULE.md Module 14). Email and role are
 * read-only (identity comes from the token); everything else is what the
 * traveller keeps on file. Never carries the password.
 */
public record ProfileResponse(
        String email,
        String fullName,
        String role,
        String phone,
        LocalDate dateOfBirth,
        String nationality,
        String passportNumber,
        LocalDate passportExpiry,
        String emergencyContactName,
        String emergencyContactPhone,
        /** Account-level preferences, applied on every sign-in; null = never chosen. */
        String preferredLanguage,
        String preferredCurrency
) {
}
