package com.skybook.praveen.authservice.entity;

/**
 * A user's authority tier (SECURITY_HARDENING_MODULE.md §4.1). Never
 * self-assignable through the public API - the register request has no role
 * field; ADMIN is granted only by the bootstrap property (§4.3). Emitted into
 * the JWT as {@code ROLE_USER} / {@code ROLE_ADMIN}.
 */
public enum UserRole {

    USER,
    ADMIN;

    /** The Spring authority form, e.g. {@code ROLE_ADMIN}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
