package com.skybook.praveen.checkinservice.domain;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Year;

/**
 * "BAG-2026-K7M4Z9" style - same generator pattern as
 * BoardingPassNumberGenerator/PaymentReferenceGenerator/PnrGenerator. Not
 * listed explicitly in the design doc's domain services table (section 10)
 * but required by Baggage.tagNumber's uniqueness constraint (section 3.3).
 */
@Component
public class BaggageTagGenerator {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int SUFFIX_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    public String generate() {
        StringBuilder suffix = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            suffix.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return "BAG-" + Year.now().getValue() + "-" + suffix;
    }
}
