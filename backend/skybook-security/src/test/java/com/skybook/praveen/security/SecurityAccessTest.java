package com.skybook.praveen.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Object-level ownership (SECURITY_HARDENING_MODULE.md §4.2). Authentication
 * alone is not authorization: these cases pin down exactly who may act on a row
 * owned by someone else, and the default when nobody is authenticated at all.
 */
class SecurityAccessTest {

    private static final String ALICE = "alice@example.com";
    private static final String BOB = "bob@example.com";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Authenticates exactly the way {@link JwtAuthenticationFilter} does. */
    private static void signedInAs(String subject, TokenType tokenType, String... roles) {
        List<String> roleList = List.of(roles);
        AuthenticatedPrincipal principal =
                new AuthenticatedPrincipal(subject, tokenType, roleList, "skybook-api");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));
    }

    private static void signedInAsUser(String subject) {
        signedInAs(subject, TokenType.USER, "ROLE_USER");
    }

    @Nested
    @DisplayName("reading the caller out of the security context")
    class CurrentCaller {

        @Test
        void reportsTheTokenSubjectOfTheSignedInCaller() {
            signedInAsUser(ALICE);

            assertThat(SecurityAccess.currentSubject()).isEqualTo(ALICE);
        }

        @Test
        void reportsNoSubjectWhenNobodyIsAuthenticated() {
            assertThat(SecurityAccess.currentSubject()).isNull();
        }

        @Test
        void reportsNoRolesWhenNobodyIsAuthenticated() {
            assertThat(SecurityAccess.hasRole("ROLE_USER")).isFalse();
            assertThat(SecurityAccess.isAdmin()).isFalse();
            assertThat(SecurityAccess.isPrivileged()).isFalse();
        }

        @Test
        void matchesOnlyTheRolesTheCallerActuallyHolds() {
            signedInAsUser(ALICE);

            assertThat(SecurityAccess.hasRole("ROLE_USER")).isTrue();
            assertThat(SecurityAccess.hasRole("ROLE_ADMIN")).isFalse();
            assertThat(SecurityAccess.hasRole("ROLE_SERVICE")).isFalse();
        }

        @Test
        void treatsAdminAsBothAdminAndPrivileged() {
            signedInAs(ALICE, TokenType.USER, "ROLE_ADMIN");

            assertThat(SecurityAccess.isAdmin()).isTrue();
            assertThat(SecurityAccess.isPrivileged()).isTrue();
        }

        @Test
        void treatsAnInternalServiceAsPrivilegedButNotAdmin() {
            // A machine caller may reach any row, but it is not an administrator -
            // anything gated on isAdmin() specifically stays closed to it.
            signedInAs("booking-service", TokenType.SERVICE, "ROLE_SERVICE");

            assertThat(SecurityAccess.isPrivileged()).isTrue();
            assertThat(SecurityAccess.isAdmin()).isFalse();
        }

        @Test
        void doesNotTreatAnOrdinaryUserAsPrivileged() {
            signedInAsUser(ALICE);

            assertThat(SecurityAccess.isPrivileged()).isFalse();
        }
    }

    @Nested
    @DisplayName("requireOwnerOrAdmin gates every cross-account access")
    class OwnershipCheck {

        @Test
        void allowsAUserToActOnTheirOwnResource() {
            signedInAsUser(ALICE);

            assertThatCode(() -> SecurityAccess.requireOwnerOrAdmin(ALICE)).doesNotThrowAnyException();
        }

        @Test
        void refusesAUserActingOnSomeoneElsesResource() {
            signedInAsUser(ALICE);

            assertThatThrownBy(() -> SecurityAccess.requireOwnerOrAdmin(BOB))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("not the owner");
        }

        @Test
        void refusesAUserWhoseSubjectMerelyLooksSimilar() {
            // Ownership is an exact match, not a prefix or case-insensitive one.
            signedInAsUser(ALICE);

            assertThatThrownBy(() -> SecurityAccess.requireOwnerOrAdmin("alice@example.com.attacker.test"))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> SecurityAccess.requireOwnerOrAdmin("ALICE@EXAMPLE.COM"))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        void allowsAnAdminOnAnyOnesResource() {
            signedInAs("root@skybook.com", TokenType.USER, "ROLE_ADMIN");

            assertThatCode(() -> SecurityAccess.requireOwnerOrAdmin(BOB)).doesNotThrowAnyException();
        }

        @Test
        void allowsAnInternalServiceOnAnyOnesResource() {
            signedInAs("booking-service", TokenType.SERVICE, "ROLE_SERVICE");

            assertThatCode(() -> SecurityAccess.requireOwnerOrAdmin(BOB)).doesNotThrowAnyException();
        }

        @Test
        void refusesAUserOnALegacyRowThatHasNoOwner() {
            // A null owner is unattributable, so it stays ADMIN/SERVICE-only
            // rather than becoming readable by whoever asks first.
            signedInAsUser(ALICE);

            assertThatThrownBy(() -> SecurityAccess.requireOwnerOrAdmin(null))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        void allowsAnAdminOnALegacyRowThatHasNoOwner() {
            signedInAs("root@skybook.com", TokenType.USER, "ROLE_ADMIN");

            assertThatCode(() -> SecurityAccess.requireOwnerOrAdmin(null)).doesNotThrowAnyException();
        }

        @Test
        void refusesAnUnauthenticatedCaller() {
            assertThatThrownBy(() -> SecurityAccess.requireOwnerOrAdmin(ALICE))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }
}
