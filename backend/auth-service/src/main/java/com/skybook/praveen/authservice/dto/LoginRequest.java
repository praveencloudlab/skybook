package com.skybook.praveen.authservice.dto;

public record LoginRequest(
        String email,
        String password
) {
}