package com.skybook.praveen.security;

import java.security.Principal;
import java.util.List;

/**
 * The verified identity extracted from a valid token
 * (SECURITY_HARDENING_MODULE.md §5), placed into the Spring
 * {@code Authentication} principal. {@code subject} is the token {@code sub}
 * (a user email, or a service client id); services compare it against
 * {@code ownerSubject} for ownership checks (§4.2).
 *
 * Implements {@link Principal} so {@code Authentication.getName()} resolves to
 * the subject - the common case (ownership) needs only the subject, while the
 * full record stays available (roles/tokenType) for callers that cast.
 */
public record AuthenticatedPrincipal(
        String subject,
        TokenType tokenType,
        List<String> roles,
        String audience
) implements Principal {

    @Override
    public String getName() {
        return subject;
    }
}
