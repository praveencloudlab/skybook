package com.skybook.praveen.security;

import java.security.Principal;
import java.util.List;

/**
 * The verified identity extracted from a valid token
 * (SECURITY_HARDENING_MODULE.md §5), placed into the Spring
 * {@code Authentication} principal. {@code subject} is the token {@code sub}
 * (a user email, a service client id, or {@code guest:<bookingId>}); services
 * compare it against {@code ownerSubject} for ownership checks (§4.2).
 *
 * <p>{@code bookingId} is the guest token's scope (GUEST_CHECKIN_MODULE.md
 * §3.3) - the ONE booking that session may touch. Null for user and service
 * tokens, whose reach is decided by subject and role, not by claim.
 *
 * Implements {@link Principal} so {@code Authentication.getName()} resolves to
 * the subject - the common case (ownership) needs only the subject, while the
 * full record stays available (roles/tokenType/bookingId) for callers that cast.
 */
public record AuthenticatedPrincipal(
        String subject,
        TokenType tokenType,
        List<String> roles,
        String audience,
        Long bookingId
) implements Principal {

    /** The pre-guest shape: user/service tokens carry no booking scope. */
    public AuthenticatedPrincipal(String subject, TokenType tokenType, List<String> roles, String audience) {
        this(subject, tokenType, roles, audience, null);
    }

    @Override
    public String getName() {
        return subject;
    }
}
