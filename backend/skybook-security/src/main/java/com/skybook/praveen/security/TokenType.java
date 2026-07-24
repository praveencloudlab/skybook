package com.skybook.praveen.security;

/**
 * The {@code token_type} claim (SECURITY_HARDENING_MODULE.md §5). A token is
 * either a human user token or a machine service token; the two carry
 * different audiences and disjoint role sets, and the validator enforces
 * strict type&harr;role coherence.
 */
public enum TokenType {

    USER("user"),
    SERVICE("service");

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
