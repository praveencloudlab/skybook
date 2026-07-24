package com.skybook.praveen.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Object-level ownership checks (SECURITY_HARDENING_MODULE.md §4.2), shared by
 * every service that stores an {@code ownerSubject}. Authentication + role is
 * not enough - a USER may act only on their own resources; ADMIN (and internal
 * SERVICE operations) may act on any. Legacy rows with a null owner are
 * ADMIN/SERVICE-only.
 */
public final class SecurityAccess {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_SERVICE = "ROLE_SERVICE";

    private SecurityAccess() {
    }

    /** The authenticated subject (token {@code sub}), or null if unauthenticated. */
    public static String currentSubject() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    public static boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority granted : auth.getAuthorities()) {
            if (role.equals(granted.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAdmin() {
        return hasRole(ROLE_ADMIN);
    }

    /** ADMIN and internal SERVICE callers bypass the per-owner check. */
    public static boolean isPrivileged() {
        return hasRole(ROLE_ADMIN) || hasRole(ROLE_SERVICE);
    }

    /**
     * Enforces that the current caller may act on a resource owned by
     * {@code ownerSubject}: ADMIN/SERVICE always may; a USER only when the
     * subjects match; a null owner (legacy row) is privileged-only.
     *
     * @throws AccessDeniedException (→ 403) otherwise
     */
    public static void requireOwnerOrAdmin(String ownerSubject) {
        if (isPrivileged()) {
            return;
        }
        String subject = currentSubject();
        if (ownerSubject != null && ownerSubject.equals(subject)) {
            return;
        }
        throw new AccessDeniedException("not the owner of this resource");
    }
}
