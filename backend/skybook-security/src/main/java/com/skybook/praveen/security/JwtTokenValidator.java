package com.skybook.praveen.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Set;

/**
 * The one shared token validator (SECURITY_HARDENING_MODULE.md §5) - the
 * gateway and every downstream service verify with this exact logic, so they
 * can never drift on algorithm, issuer, audience or role rules.
 *
 * Verifies with the RS256 PUBLIC key only: this class can validate a token but
 * can never mint one. The full checklist, every item required:
 *
 * <pre>
 *   signature valid (RS256, public key)
 *   alg == RS256                          (reject alg:none / RS→HS substitution)
 *   iss == configured issuer
 *   sub present
 *   token_type present and recognized (user | service)
 *   token_type ↔ role coherence           (user → {USER}|{ADMIN}; service → {SERVICE})
 *   aud matches the per-token-type rule    (user → user-audience; service → this service)
 *   exp in the future / iat present        (enforced by the jjwt parser)
 * </pre>
 *
 * Any failure throws {@link InvalidTokenException} - fail closed, never a
 * silent default to USER.
 */
public class JwtTokenValidator {

    private static final String ROLE_USER = "ROLE_USER";
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_SERVICE = "ROLE_SERVICE";

    private final RSAPublicKey publicKey;
    private final JwtSecurityProperties properties;

    public JwtTokenValidator(RSAPublicKey publicKey, JwtSecurityProperties properties) {
        this.publicKey = publicKey;
        this.properties = properties;
    }

    public AuthenticatedPrincipal validate(String token) {

        Jws<Claims> jws;
        try {
            // requireIssuer + the RSAPublicKey pin the issuer and signature;
            // jjwt enforces exp/iat/nbf during parse.
            jws = Jwts.parser()
                    .verifyWith(publicKey)
                    .requireIssuer(properties.getIssuer())
                    .build()
                    .parseSignedClaims(token);
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("token signature/issuer/expiry invalid: " + e.getMessage(), e);
        }

        // alg pin: reject anything that isn't RS256 (defends against alg:none
        // and the RS→HS confusion attack even though we hold no HMAC secret).
        String alg = jws.getHeader().getAlgorithm();
        if (!"RS256".equals(alg)) {
            throw new InvalidTokenException("unexpected JWT alg: " + alg);
        }

        Claims claims = jws.getPayload();

        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new InvalidTokenException("token has no subject");
        }
        if (claims.getIssuedAt() == null) {
            throw new InvalidTokenException("token has no iat");
        }
        // jjwt only *checks* exp when it is present, so a token minted without an
        // exp claim would never expire. Require it explicitly - fail closed.
        if (claims.getExpiration() == null) {
            throw new InvalidTokenException("token has no exp");
        }

        TokenType tokenType = TokenType.fromClaim(claims.get("token_type", String.class));
        if (tokenType == null) {
            throw new InvalidTokenException("missing or unrecognized token_type");
        }
        if (tokenType == TokenType.SERVICE && !properties.isAcceptServiceTokens()) {
            throw new InvalidTokenException("service tokens are not accepted here");
        }

        List<String> roles = readRoles(claims);
        requireCoherentRoles(tokenType, roles);
        requireAudience(tokenType, claims.getAudience());

        return new AuthenticatedPrincipal(subject, tokenType, roles, requiredAudienceFor(tokenType));
    }

    @SuppressWarnings("unchecked")
    private List<String> readRoles(Claims claims) {
        Object raw = claims.get("roles");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new InvalidTokenException("token has no roles");
        }
        return list.stream().map(String::valueOf).toList();
    }

    /**
     * Strict token_type↔role coherence (§5): a recognized type AND a recognized
     * role is not enough - only these exact combinations are valid, everything
     * else (incl. mixed-role tokens) is rejected.
     */
    private void requireCoherentRoles(TokenType tokenType, List<String> roles) {
        Set<String> set = Set.copyOf(roles);
        boolean ok = switch (tokenType) {
            case USER -> set.equals(Set.of(ROLE_USER)) || set.equals(Set.of(ROLE_ADMIN));
            case SERVICE -> set.equals(Set.of(ROLE_SERVICE));
        };
        if (!ok) {
            throw new InvalidTokenException(
                    "token_type " + tokenType.claimValue() + " incoherent with roles " + roles);
        }
    }

    private void requireAudience(TokenType tokenType, Set<String> audience) {
        String required = requiredAudienceFor(tokenType);
        if (required == null || required.isBlank()) {
            throw new InvalidTokenException("no audience configured for token_type " + tokenType.claimValue());
        }
        if (audience == null || !audience.contains(required)) {
            throw new InvalidTokenException("audience " + audience + " does not contain required " + required);
        }
    }

    private String requiredAudienceFor(TokenType tokenType) {
        return switch (tokenType) {
            case USER -> properties.getUserAudience();
            case SERVICE -> properties.getServiceAudience();
        };
    }
}
