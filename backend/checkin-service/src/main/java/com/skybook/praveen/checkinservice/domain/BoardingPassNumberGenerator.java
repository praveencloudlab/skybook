package com.skybook.praveen.checkinservice.domain;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Year;

/**
 * Human-readable references in the PNR philosophy - "BP-2026-K7M4Z9", not
 * UUIDs (design doc section 10, same generator pattern as
 * PaymentReferenceGenerator/PnrGenerator). Ambiguous characters (0/O, 1/I)
 * excluded. Uniqueness is guaranteed by the DB constraint; callers
 * re-generate on the (rare) collision.
 */
@Component
public class BoardingPassNumberGenerator {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int SUFFIX_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    public String generate() {
        StringBuilder suffix = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            suffix.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return "BP-" + Year.now().getValue() + "-" + suffix;
    }
}
