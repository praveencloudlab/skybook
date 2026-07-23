package com.skybook.praveen.security;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The §5 validation checklist, exercised against RSA-signed tokens. The
 * validator holds only the public key, so nothing here can forge a token the
 * long way round - the negative cases prove each rule fails closed.
 */
class JwtTokenValidatorTest {

    private final TestTokens tokens = TestTokens.rsa2048();
    private final JwtTokenValidator validator =
            new JwtTokenValidator(tokens.publicKey(), tokens.properties());

    @Test
    void acceptsAValidUserToken() {
        AuthenticatedPrincipal principal = validator.validate(tokens.token().sign());

        assertThat(principal.subject()).isEqualTo("alice@example.com");
        assertThat(principal.tokenType()).isEqualTo(TokenType.USER);
        assertThat(principal.roles()).containsExactly("ROLE_USER");
        assertThat(principal.audience()).isEqualTo(TestTokens.USER_AUDIENCE);
    }

    @Test
    void acceptsAValidAdminUserToken() {
        AuthenticatedPrincipal principal = validator.validate(
                tokens.token().roles("ROLE_ADMIN").sign());

        assertThat(principal.roles()).containsExactly("ROLE_ADMIN");
    }

    @Test
    void acceptsAValidServiceToken() {
        AuthenticatedPrincipal principal = validator.validate(tokens.token()
                .tokenType("service").roles("ROLE_SERVICE")
                .audience(TestTokens.SERVICE_AUDIENCE).subject("booking-service").sign());

        assertThat(principal.tokenType()).isEqualTo(TokenType.SERVICE);
        assertThat(principal.subject()).isEqualTo("booking-service");
        assertThat(principal.audience()).isEqualTo(TestTokens.SERVICE_AUDIENCE);
    }

    @Test
    void rejectsAServiceTokenWhenServiceTokensAreNotAccepted() {
        // The gateway configuration (acceptServiceTokens=false): a machine token
        // must never enter through the public edge.
        JwtSecurityProperties gatewayProps = tokens.properties();
        gatewayProps.setAcceptServiceTokens(false);
        JwtTokenValidator gatewayValidator = new JwtTokenValidator(tokens.publicKey(), gatewayProps);

        assertThatThrownBy(() -> gatewayValidator.validate(tokens.token()
                .tokenType("service").roles("ROLE_SERVICE").audience(TestTokens.SERVICE_AUDIENCE).sign()))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("service tokens are not accepted");
    }

    @Test
    void rejectsAWrongIssuer() {
        assertThatThrownBy(() -> validator.validate(tokens.token().issuer("evil-issuer").sign()))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsAUserTokenWithTheServiceAudience() {
        assertThatThrownBy(() -> validator.validate(
                tokens.token().audience(TestTokens.SERVICE_AUDIENCE).sign()))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("audience");
    }

    @Test
    void rejectsAServiceTokenWithTheWrongServiceAudience() {
        assertThatThrownBy(() -> validator.validate(tokens.token()
                .tokenType("service").roles("ROLE_SERVICE").audience("flight-service").sign()))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("audience");
    }

    @Test
    void rejectsAMissingTokenType() {
        assertThatThrownBy(() -> validator.validate(tokens.token().tokenType(null).sign()))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("token_type");
    }

    @Test
    void rejectsAUserTokenCarryingRoleService() {
        assertThatThrownBy(() -> validator.validate(tokens.token().roles("ROLE_SERVICE").sign()))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("incoherent");
    }

    @Test
    void rejectsAServiceTokenCarryingRoleAdmin() {
        assertThatThrownBy(() -> validator.validate(tokens.token()
                .tokenType("service").roles("ROLE_ADMIN").audience(TestTokens.SERVICE_AUDIENCE).sign()))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("incoherent");
    }

    @Test
    void rejectsAMixedRoleToken() {
        assertThatThrownBy(() -> validator.validate(tokens.token()
                .tokenType("service").roles("ROLE_SERVICE", "ROLE_ADMIN")
                .audience(TestTokens.SERVICE_AUDIENCE).sign()))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("incoherent");
    }

    @Test
    void rejectsAnExpiredToken() {
        assertThatThrownBy(() -> validator.validate(
                tokens.token().expiry(Instant.now().minus(1, ChronoUnit.MINUTES)).sign()))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsATokenWithNoSubject() {
        assertThatThrownBy(() -> validator.validate(tokens.token().subject(null).sign()))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("subject");
    }

    @Test
    void rejectsATokenWithNoIssuedAt() {
        assertThatThrownBy(() -> validator.validate(tokens.token().noIssuedAt().sign()))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("iat");
    }

    @Test
    void rejectsATokenWithNoExpiry() {
        // jjwt only validates exp when it is present; an otherwise-valid RS256
        // token minted without exp would never expire, so the validator must
        // require it explicitly.
        assertThatThrownBy(() -> validator.validate(tokens.token().noExpiry().sign()))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("exp");
    }

    @Test
    void rejectsATokenSignedByADifferentKey() {
        // A token minted with another RSA private key (a would-be forger who does
        // NOT hold auth's private key) fails signature verification.
        TestTokens attacker = TestTokens.rsa2048();
        assertThatThrownBy(() -> validator.validate(attacker.token().sign()))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsAnHmacSignedTokenAgainstTheRsaKey() {
        // RS→HS confusion attempt: an HS256 token can't verify against an RSA
        // public key, and the alg pin would reject it regardless.
        var hmacKey = new SecretKeySpec("a".repeat(64).getBytes(), "HmacSHA256");
        String hs256 = Jwts.builder()
                .subject("alice@example.com")
                .issuer(TestTokens.ISSUER)
                .audience().add(TestTokens.USER_AUDIENCE).and()
                .claims(Map.of("token_type", "user", "roles", java.util.List.of("ROLE_USER")))
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(60, ChronoUnit.MINUTES)))
                .signWith(hmacKey, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> validator.validate(hs256))
                .isInstanceOf(InvalidTokenException.class);
    }
}
