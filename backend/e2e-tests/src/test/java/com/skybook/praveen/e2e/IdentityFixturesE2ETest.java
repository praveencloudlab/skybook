package com.skybook.praveen.e2e;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Certifies the identity fixtures themselves (build-order step 2).
 *
 * <p>Everything downstream — OWNER-scoping, cross-user 403s, back-office
 * assertions — is only meaningful if these are actually distinct principals with
 * the roles they claim. A fixture bug here would silently weaken every later
 * test (e.g. two "different" users that are really the same account would make a
 * cross-user isolation test pass for the wrong reason).
 */
@DisplayName("Identity fixtures: distinct principals with the right roles")
class IdentityFixturesE2ETest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssured.baseURI = E2EConfig.BASE_URL;
    }

    @Test
    @DisplayName("admin carries ROLE_ADMIN and is cached, not re-minted")
    void adminIsAdminAndCached() {
        String first = Identities.adminToken();
        String second = Identities.adminToken();

        assertThat(Jwt.roles(first)).containsExactly("ROLE_ADMIN");
        assertThat(second)
                .as("repeat calls should reuse the cached token rather than logging in again")
                .isSameAs(first);
    }

    @Test
    @DisplayName("a fresh passenger is ROLE_USER and owns its own subject")
    void freshUserIsAPlainPassenger() {
        E2EUser user = Identities.newUser("owner");

        assertThat(user.roles())
                .as("registration must never yield elevated privileges")
                .containsExactly("ROLE_USER");
        assertThat(user.subject())
                .as("sub is the normalised email - this is what OWNER checks compare against")
                .isEqualTo(user.email().toLowerCase());
    }

    @Test
    @DisplayName("two fresh passengers are genuinely different principals")
    void usersAreDistinct() {
        E2EUser owner = Identities.newUser("owner");
        E2EUser intruder = Identities.newUser("intruder");

        assertThat(owner.email()).isNotEqualTo(intruder.email());
        assertThat(owner.subject())
                .as("""
                        If these collided, every later cross-user isolation test would pass for
                        the wrong reason - the "intruder" would actually be the owner.""")
                .isNotEqualTo(intruder.subject());
        assertThat(owner.token()).isNotEqualTo(intruder.token());
    }

    @Test
    @DisplayName("a passenger token is rejected on an ADMIN-only surface")
    void passengerCannotReachAdminSurface() {
        E2EUser user = Identities.newUser("plain");

        int status = RestAssured.given()
                .header("Authorization", user.bearer())
                .when()
                .get("/api/bookings")
                .statusCode();

        assertThat(status)
                .as("list-all-bookings is back-office (ADMIN); a passenger must be forbidden")
                .isEqualTo(403);
    }
}
