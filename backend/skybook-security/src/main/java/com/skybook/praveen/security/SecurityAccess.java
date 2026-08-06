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
    private static final String ROLE_GUEST = "ROLE_GUEST";

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

    /** True when the caller authenticated with a booking-scoped guest token. */
    public static boolean isGuest() {
        return hasRole(ROLE_GUEST);
    }

    /**
     * The guest session's one permitted booking id, or null for every other
     * caller (GUEST_CHECKIN_MODULE.md §3.3).
     */
    public static Long guestBookingId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedPrincipal principal) {
            return principal.bookingId();
        }
        return null;
    }

    /**
     * The booking-aware ownership check (GUEST_CHECKIN_MODULE.md §3.3):
     * ADMIN/SERVICE always may; a USER when the subjects match; a GUEST when
     * - and only when - the resource belongs to the one booking its token is
     * scoped to.
     *
     * <p>Failure is deliberately asymmetric (decision D8): a guest outside its
     * scope gets {@link ResourceConcealedException} (→ 404, the resource does
     * not exist for them); every other refusal stays
     * {@link AccessDeniedException} (→ 403), because for account holders a 403
     * on an opaque id reveals nothing, and downgrading it would hide real
     * authorization bugs during development.
     */
    public static void requireBookingAccess(String ownerSubject, Long bookingId) {
        if (isPrivileged()) {
            return;
        }
        if (isGuest()) {
            Long scope = guestBookingId();
            if (scope != null && scope.equals(bookingId)) {
                return;
            }
            throw new ResourceConcealedException();
        }
        String subject = currentSubject();
        if (ownerSubject != null && ownerSubject.equals(subject)) {
            return;
        }
        throw new AccessDeniedException("not the owner of this resource");
    }
}
