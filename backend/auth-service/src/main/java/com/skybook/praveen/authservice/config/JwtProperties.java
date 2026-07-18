package com.skybook.praveen.authservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds {@code jwt.*} for auth-service, the fleet's only token minter
 * (SECURITY_HARDENING_MODULE.md §5). RS256: a private signing key that lives
 * nowhere else, plus the public key it also verifies its own tokens with.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** RS256 PRIVATE key (PEM PKCS#8). Only auth-service holds this. */
    private String privateKey;

    /** RS256 PUBLIC key (PEM). Used to verify auth-service's own tokens (e.g. /profile). */
    private String publicKey;

    /** {@code iss} claim, environment-specific, e.g. {@code skybook-auth-prod}. */
    private String issuer;

    /** {@code aud} claim for user tokens, e.g. {@code skybook-api-prod}. */
    private String audience;

    /** Access-token lifetime in ms. USER tokens = 60 min (§5). */
    private long expiration;
}
