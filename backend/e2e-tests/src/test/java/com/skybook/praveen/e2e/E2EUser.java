package com.skybook.praveen.e2e;

import java.util.List;

/**
 * A passenger account this run created, with its bearer token.
 *
 * <p>{@link #subject()} is what OWNER checks compare against: auth-service mints
 * the token with the (normalised, lower-cased) email as {@code sub}, and every
 * service's ownership guard compares {@code ownerSubject} to that claim.
 */
public record E2EUser(String email, String password, String token) {

    public String subject() {
        return Jwt.subject(token);
    }

    public List<String> roles() {
        return Jwt.roles(token);
    }

    /** Convenience for RestAssured's {@code .header("Authorization", ...)}. */
    public String bearer() {
        return "Bearer " + token;
    }
}
