package com.skybook.praveen.authservice.dto;

public record RegisterRequest(
        String fullName,
        String email,
        String password


) {
}