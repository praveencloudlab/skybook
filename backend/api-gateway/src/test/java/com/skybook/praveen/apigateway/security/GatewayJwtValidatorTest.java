package com.skybook.praveen.apigateway.security;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayJwtValidatorTest {

    private static final String SECRET = "test-secret-key-that-is-long-enough-for-hmac-sha256";

    private static GatewayJwtValidator validator() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        return new GatewayJwtValidator(properties);
    }

    private static SecretKey keyFor(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void extractsTheSubjectFromAValidlySignedToken() {
        String token = Jwts.builder()
                .subject("traveler@skybook.com")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(keyFor(SECRET))
                .compact();

        assertThat(validator().validateAndExtractSubject(token)).isEqualTo("traveler@skybook.com");
    }

    @Test
    void rejectsAnExpiredToken() {
        String token = Jwts.builder()
                .subject("traveler@skybook.com")
                .issuedAt(new Date(System.currentTimeMillis() - 120_000))
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(keyFor(SECRET))
                .compact();

        assertThatThrownBy(() -> validator().validateAndExtractSubject(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsATokenSignedWithADifferentSecret() {
        String token = Jwts.builder()
                .subject("traveler@skybook.com")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(keyFor("a-completely-different-secret-key-thats-long-enough"))
                .compact();

        assertThatThrownBy(() -> validator().validateAndExtractSubject(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsAMalformedToken() {
        assertThatThrownBy(() -> validator().validateAndExtractSubject("not-a-real-token"))
                .isInstanceOf(JwtException.class);
    }
}
