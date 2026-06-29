package com.skybook.praveen.authservice.service;

import com.skybook.praveen.authservice.config.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret("this-is-a-very-long-secret-key-for-jwt-testing-123456");
        jwtProperties.setExpiration(3600000L);

        jwtService = new JwtService(jwtProperties);
    }

    @Test
    void shouldGenerateToken() {
        String token = jwtService.generateToken("test123@gmail.com");

        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    void shouldExtractUsernameFromToken() {
        String token = jwtService.generateToken("test123@gmail.com");

        String username = jwtService.extractUsername(token);

        assertEquals("test123@gmail.com", username);
    }

    @Test
    void shouldValidateToken() {
        String token = jwtService.generateToken("test123@gmail.com");

        boolean valid = jwtService.isTokenValid(token, "test123@gmail.com");

        assertTrue(valid);
    }

    @Test
    void shouldRejectTokenForDifferentEmail() {
        String token = jwtService.generateToken("test123@gmail.com");

        boolean valid = jwtService.isTokenValid(token, "wrong@gmail.com");

        assertFalse(valid);
    }
}