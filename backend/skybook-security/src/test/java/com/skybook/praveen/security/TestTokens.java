package com.skybook.praveen.security;

import io.jsonwebtoken.Jwts;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Test-only token minting: generates an RSA keypair and signs tokens with the
 * private key the way auth-service will, so the validator can be exercised with
 * the matching public key. No keys are committed - each run is fresh.
 */
final class TestTokens {

    static final String ISSUER = "skybook-auth-test";
    static final String USER_AUDIENCE = "skybook-api-test";
    static final String SERVICE_AUDIENCE = "inventory-service";

    private final KeyPair keyPair;

    private TestTokens(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    static TestTokens rsa2048() {
        return new TestTokens(generate(2048));
    }

    static KeyPair generate(int bits) {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(bits);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    RSAPublicKey publicKey() {
        return (RSAPublicKey) keyPair.getPublic();
    }

    String publicKeyPem() {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }

    /** Single-line, armor-free base64 - survives Spring property injection. */
    String publicKeyBase64OneLine() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    JwtSecurityProperties properties() {
        JwtSecurityProperties p = new JwtSecurityProperties();
        p.setPublicKey(publicKeyPem());
        p.setIssuer(ISSUER);
        p.setUserAudience(USER_AUDIENCE);
        p.setServiceAudience(SERVICE_AUDIENCE);
        return p;
    }

    Builder token() {
        return new Builder((RSAPrivateKey) keyPair.getPrivate());
    }

    static final class Builder {
        private final RSAPrivateKey signingKey;
        private String subject = "alice@example.com";
        private String issuer = ISSUER;
        private String audience = USER_AUDIENCE;
        private String tokenType = "user";
        private List<String> roles = List.of("ROLE_USER");
        private Instant issuedAt = Instant.now();
        private Instant expiry = Instant.now().plus(60, ChronoUnit.MINUTES);
        private boolean omitIssuedAt = false;

        Builder(RSAPrivateKey signingKey) {
            this.signingKey = signingKey;
        }

        Builder subject(String s) { this.subject = s; return this; }
        Builder issuer(String s) { this.issuer = s; return this; }
        Builder audience(String s) { this.audience = s; return this; }
        Builder tokenType(String s) { this.tokenType = s; return this; }
        Builder roles(String... r) { this.roles = List.of(r); return this; }
        Builder expiry(Instant i) { this.expiry = i; return this; }
        Builder noIssuedAt() { this.omitIssuedAt = true; return this; }

        String sign() {
            var b = Jwts.builder()
                    .claims(claims())
                    .expiration(Date.from(expiry))
                    .signWith(signingKey, Jwts.SIG.RS256);
            if (subject != null) {
                b.subject(subject);
            }
            if (issuer != null) {
                b.issuer(issuer);
            }
            if (audience != null) {
                b.audience().add(audience).and();
            }
            if (!omitIssuedAt) {
                b.issuedAt(Date.from(issuedAt));
            }
            return b.compact();
        }

        private Map<String, Object> claims() {
            if (tokenType == null && roles == null) {
                return Map.of();
            }
            if (tokenType == null) {
                return Map.of("roles", roles);
            }
            if (roles == null) {
                return Map.of("token_type", tokenType);
            }
            return Map.of("token_type", tokenType, "roles", roles);
        }
    }
}
