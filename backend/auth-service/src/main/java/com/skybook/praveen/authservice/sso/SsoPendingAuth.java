package com.skybook.praveen.authservice.sso;

import java.io.Serializable;

/**
 * Everything the callback needs that CANNOT be derived from configuration
 * (SSO_MODULE.md §3.3): the three per-flow protocol secrets, plus SkyBook's
 * two carried values. Deliberately NOT the serialized
 * {@code OAuth2AuthorizationRequest}: the first live deployment proved that
 * object seals to ~4.5 KB of cookie - past nginx's default proxy_buffer_size
 * (a 502 at the frontend's /api proxy) and past the browser's own 4096-byte
 * per-cookie cap (a silently dropped cookie and a flow that can never
 * complete). Everything else the request object carried - client id, scopes,
 * redirect URI, authorization URI - is configuration, and configuration is
 * rebuilt at load time from the registration, not round-tripped through the
 * browser. Sealed size: ~400 bytes.
 */
public record SsoPendingAuth(
        String state,
        String nonce,
        String codeVerifier,
        boolean remember,
        String returnTo
) implements Serializable {
}
