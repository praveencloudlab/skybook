package com.skybook.praveen.security;

/**
 * The {@code token_type} claim (SECURITY_HARDENING_MODULE.md §5,
 * GUEST_CHECKIN_MODULE.md §3.1). A token is a human user token, a machine
 * service token, or a booking-scoped guest token; the three carry different
 * audiences and disjoint role sets, and the validator enforces strict
 * type&harr;role coherence.
 */
public enum TokenType {

    USER("user"),
    SERVICE("service"),
    /**
     * A no-account check-in session (GUEST_CHECKIN_MODULE.md): minted from a
     * booking reference + passenger surname, scoped to exactly one booking via
     * the {@code booking_id} claim, and accepted only by services that opted
     * in ({@code accept-guest-tokens}, default false).
     */
    GUEST("guest");

    private final String claim;

    TokenType(String claim) {
        this.claim = claim;
    }

    public String claimValue() {
        return claim;
    }

    /** Parses the raw {@code token_type} claim, or null if unrecognized (→ fail closed). */
    public static TokenType fromClaim(String value) {
        if (value == null) {
            return null;
        }
        for (TokenType type : values()) {
            if (type.claim.equals(value)) {
                return type;
            }
        }
        return null;
    }
}
