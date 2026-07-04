package com.skybook.praveen.paymentservice.domain;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Year;

/**
 * Human-readable references in the PNR philosophy - "PAY-2026-K7M4Z9", not
 * UUIDs (design doc section 12). Support staff can read these over the
 * phone; ambiguous characters (0/O, 1/I) are excluded. Uniqueness is
 * guaranteed by the DB constraint; callers re-generate on the (rare)
 * collision, PnrGenerator precedent.
 */
@Component
public class PaymentReferenceGenerator {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int SUFFIX_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    public String paymentReference() {
        return generate("PAY");
    }

    public String transactionReference() {
        return generate("TXN");
    }

    public String refundReference() {
        return generate("REF");
    }

    private String generate(String prefix) {
        StringBuilder suffix = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            suffix.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return prefix + "-" + Year.now().getValue() + "-" + suffix;
    }
}
