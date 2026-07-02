package com.skybook.praveen.bookingservice.domain;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates PNR candidates - "SB" + 4 characters drawn from an alphabet that
 * excludes visually ambiguous characters (0/O, 1/I/L), since PNRs get read
 * aloud and typed by agents and passengers (docs section 5).
 *
 * Pure, no I/O, no uniqueness check - collision handling (retry against the
 * DB unique constraint on bookingReference) is BookingServiceImpl's job,
 * since only it knows how to ask the repository whether a candidate is
 * already taken.
 */
@Component
public class PnrGenerator {

    private static final String PREFIX = "SB";

    // Excludes 0, O, 1, I, L.
    private static final String CHARSET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

    private static final int SUFFIX_LENGTH = 4;

    private final SecureRandom random = new SecureRandom();

    public String generateCandidate() {

        StringBuilder suffix = new StringBuilder(SUFFIX_LENGTH);

        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            suffix.append(CHARSET.charAt(random.nextInt(CHARSET.length())));
        }

        return PREFIX + suffix;
    }
}
