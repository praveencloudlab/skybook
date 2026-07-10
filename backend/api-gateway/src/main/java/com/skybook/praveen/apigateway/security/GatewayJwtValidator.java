package com.skybook.praveen.apigateway.security;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * Verifies (never issues) tokens signed by auth-service's JwtService - same
 * HMAC key derivation (raw UTF-8 bytes of the shared secret) and the same
 * jjwt parser call, so a token auth-service considers valid, the gateway
 * considers valid, and vice versa. Deliberately duplicated rather than
 * shared via skybook-common: pulling JWT signing into a shared library
 * would mean every other service gets jjwt on its classpath whether it
 * needs it or not, for the sake of ~15 lines of key-derivation code.
 */
@Component
@EnableConfigurationProperties(JwtProperties.class)
public class GatewayJwtValidator {

    private final JwtProperties jwtProperties;

    public GatewayJwtValidator(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    /**
     * Returns the token's subject (the email auth-service put in it) if the
     * token is validly signed and not expired.
     *
     * @throws JwtException if the token is missing, malformed, expired, or the signature doesn't match
     */
    public String validateAndExtractSubject(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    private SecretKey signingKey() {
        byte[] keyBytes = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
