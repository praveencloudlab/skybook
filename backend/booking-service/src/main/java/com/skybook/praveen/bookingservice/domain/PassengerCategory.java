package com.skybook.praveen.bookingservice.domain;

import java.time.LocalDate;
import java.time.Period;

/**
 * Age category of a passenger, derived from date of birth.
 *
 * <p>Used by the passenger-cancellation guardian rule: a minor (child or infant)
 * cannot remain on a booking with no adult, so cancelling every adult forces the
 * whole booking to be cancelled. Thresholds follow the usual airline bands.
 */
public enum PassengerCategory {
    ADULT,
    CHILD,
    INFANT;

    /** Category on a given date (the flight date, or today as a stable proxy). */
    public static PassengerCategory of(LocalDate dateOfBirth, LocalDate onDate) {
        if (dateOfBirth == null) {
            // No DOB on file - treat as an adult so it never triggers the
            // guardian rule spuriously.
            return ADULT;
        }
        int years = Period.between(dateOfBirth, onDate).getYears();
        if (years < 2) {
            return INFANT;
        }
        if (years < 12) {
            return CHILD;
        }
        return ADULT;
    }

    public boolean isMinor() {
        return this != ADULT;
    }
}
