package com.skybook.praveen.authservice.service;

import com.skybook.praveen.authservice.config.JwtProperties;
import com.skybook.praveen.authservice.config.RsaKeys;
import com.skybook.praveen.authservice.entity.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;

/**
 * Mints and verifies auth-service's RS256 tokens (SECURITY_HARDENING_MODULE.md
 * §5). Signs with the private key (auth-service is the fleet's only minter);
 * verifies its own tokens with the public key. Every token carries the full
 * claim set the shared validator requires: {@code roles}, {@code token_type=user},
 * {@code iss}, {@code aud}, {@code sub}, {@code iat}, {@code exp}.
 */
@Service
public class JwtService {

    private final JwtProperties properties;
    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void loadKeys() {
        this.privateKey = RsaKeys.privateKey(properties.getPrivateKey());
        this.publicKey = RsaKeys.publicKey(properties.getPublicKey());
    }

    public String generateToken(String email, UserRole role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + properties.getExpiration());

        return Jwts.builder()
                .subject(email)
                .issuer(properties.getIssuer())
                .audience().add(properties.getAudience()).and()
                .claim("token_type", "user")
                .claim("roles", List.of(role.authority()))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    /**
     * A short-lived {@code ROLE_SERVICE} token for a machine caller
     * (SECURITY_HARDENING_MODULE.md §3.3). {@code sub} is the authenticated
     * client id (never a request-supplied name); {@code aud} is the target
     * service. Shorter TTL than a user token - refresh is automatic and
     * invisible to users.
     */
    public String generateServiceToken(String clientId, String audience) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + properties.getServiceExpiration());

        return Jwts.builder()
                .subject(clientId)
                .issuer(properties.getIssuer())
                .audience().add(audience).and()
                .claim("token_type", "service")
                .claim("roles", List.of("ROLE_SERVICE"))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    public String extractUsername(String token) {
        return parse(token).getPayload().getSubject();
    }

    public boolean isTokenValid(String token, String email) {
        try {
            Claims claims = parse(token).getPayload();
            return email.equals(claims.getSubject()) && claims.getExpiration().after(new Date());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Jws<Claims> parse(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .requireIssuer(properties.getIssuer())
                .build()
                .parseSignedClaims(token);
    }
}
