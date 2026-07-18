package com.skybook.praveen.authservice.service;

import com.skybook.praveen.authservice.config.JwtProperties;
import com.skybook.praveen.authservice.entity.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private JwtService jwtService;
    private RSAPublicKey publicKey;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair keyPair = gen.generateKeyPair();
        publicKey = (RSAPublicKey) keyPair.getPublic();

        JwtProperties props = new JwtProperties();
        props.setPrivateKey(pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
        props.setPublicKey(pem("PUBLIC KEY", keyPair.getPublic().getEncoded()));
        props.setIssuer("skybook-auth-test");
        props.setAudience("skybook-api-test");
        props.setExpiration(3600000L);

        jwtService = new JwtService(props);
        jwtService.loadKeys();
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder().encodeToString(der)
                + "\n-----END " + type + "-----";
    }

    @Test
    void generatesAnRs256TokenWithTheFullClaimSet() {
        String token = jwtService.generateToken("test123@gmail.com", UserRole.USER);
        assertNotNull(token);

        Claims claims = Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token).getPayload();
        assertEquals("test123@gmail.com", claims.getSubject());
        assertEquals("skybook-auth-test", claims.getIssuer());
        assertTrue(claims.getAudience().contains("skybook-api-test"));
        assertEquals("user", claims.get("token_type", String.class));
        assertEquals(List.of("ROLE_USER"), claims.get("roles", List.class));
        assertNotNull(claims.getIssuedAt());
    }

    @Test
    void emitsTheAdminRoleForAnAdminUser() {
        String token = jwtService.generateToken("admin@skybook.com", UserRole.ADMIN);
        Claims claims = Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token).getPayload();
        assertEquals(List.of("ROLE_ADMIN"), claims.get("roles", List.class));
    }

    @Test
    void extractsUsernameFromItsOwnToken() {
        String token = jwtService.generateToken("test123@gmail.com", UserRole.USER);
        assertEquals("test123@gmail.com", jwtService.extractUsername(token));
    }

    @Test
    void validatesItsOwnToken() {
        String token = jwtService.generateToken("test123@gmail.com", UserRole.USER);
        assertTrue(jwtService.isTokenValid(token, "test123@gmail.com"));
    }

    @Test
    void rejectsTokenForADifferentEmail() {
        String token = jwtService.generateToken("test123@gmail.com", UserRole.USER);
        assertFalse(jwtService.isTokenValid(token, "wrong@gmail.com"));
    }
}
