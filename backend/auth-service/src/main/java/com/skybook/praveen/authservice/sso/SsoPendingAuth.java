package com.skybook.praveen.authservice.sso;

import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.io.Serializable;

/**
 * Everything the callback needs to finish what the start request began
 * (SSO_MODULE.md §3.3): Spring's in-flight authorization request (state, nonce,
 * PKCE verifier) plus SkyBook's two carried values. Travels encrypted inside
 * the pending-auth cookie - auth-service holds no HTTP session, so the browser
 * carries the state and cryptography keeps it honest.
 */
public record SsoPendingAuth(
        OAuth2AuthorizationRequest request,
        boolean remember,
        String returnTo
) implements Serializable {
}
