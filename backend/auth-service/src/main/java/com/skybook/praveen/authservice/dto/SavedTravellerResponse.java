package com.skybook.praveen.authservice.dto;

import java.time.LocalDate;

/** A saved traveller as returned to its owner (FRONTEND_MODULE.md Module 14). */
public record SavedTravellerResponse(
        Long id,
        String title,
        String firstName,
        String lastName,
        LocalDate dateOfBirth,
        String nationality,
        String passportNumber,
        LocalDate passportExpiry
) {
}
