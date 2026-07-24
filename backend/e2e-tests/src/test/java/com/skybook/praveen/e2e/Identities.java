package com.skybook.praveen.e2e;

import io.restassured.RestAssured;
import io.restassured.response.Response;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Identity fixtures (E2E_CERTIFICATION_MODULE.md §4, build-order step 2).
 *
 * <p>Two identities exist for different reasons:
 * <ul>
 *   <li><b>ADMIN</b> — one per environment, arranged out of band via
 *       {@code SKYBOOK_BOOTSTRAP_ADMIN_EMAIL}. Cached, because logging in
 *       repeatedly proves nothing.</li>
 *   <li><b>USER</b> — a <b>fresh account per call</b>. Runs must never collide,
 *       and OWNER-scoping is only genuinely exercised when the owner is an
 *       account this run actually created.</li>
 * </ul>
 *
 * <p>There is deliberately no cleanup: runs are isolated by unique identity, not
 * by resetting shared state, so the suite can run repeatedly (and concurrently)
 * against a long-lived environment.
 */
public final class Identities {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static String cachedAdminToken;

    private Identities() {
    }

    /** The bootstrapped ADMIN. Fails loudly if the environment has none. */
    public static synchronized String adminToken() {
        if (cachedAdminToken != null) {
            return cachedAdminToken;
        }
        if (!E2EConfig.adminConfigured()) {
            throw new IllegalStateException("""
                    No ADMIN credentials supplied (-De2e.admin.email / -De2e.admin.password,
                    or E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD). See E2E_CERTIFICATION_MODULE.md §4.""");
        }
        String token = login(E2EConfig.ADMIN_EMAIL, E2EConfig.ADMIN_PASSWORD);
        List<String> roles = Jwt.roles(token);
        if (roles == null || !roles.contains("ROLE_ADMIN")) {
            throw new IllegalStateException(
                    "Configured admin '%s' logged in but carries %s, not ROLE_ADMIN. "
                            + "SKYBOOK_BOOTSTRAP_ADMIN_EMAIL must match it exactly, and auth-service "
                            + "must have restarted since - promotion happens once, at startup."
                            .formatted(E2EConfig.ADMIN_EMAIL, roles));
        }
        cachedAdminToken = token;
        return token;
    }

    /**
     * Registers and logs in a brand-new passenger.
     *
     * @param label appears in the address purely to make failures readable
     *              (e.g. "owner" vs "intruder")
     */
    public static E2EUser newUser(String label) {
        String email = "e2e-%s-%s-%d@skybook.com"
                .formatted(label, E2EConfig.RUN_ID, SEQUENCE.incrementAndGet());

        Response registration = RestAssured.given()
                .contentType("application/json")
                .body("""
                        {"fullName":"E2E %s","email":"%s","password":"%s"}"""
                        .formatted(label, email, E2EConfig.USER_PASSWORD))
                .when()
                .post("/api/auth/register");

        if (registration.statusCode() != 200) {
            throw new IllegalStateException("Could not register %s: %d %s"
                    .formatted(email, registration.statusCode(), registration.asString()));
        }

        String token = login(email, E2EConfig.USER_PASSWORD);
        return new E2EUser(email, E2EConfig.USER_PASSWORD, token);
    }

    private static String login(String email, String password) {
        Response response = RestAssured.given()
                .contentType("application/json")
                .body("""
                        {"email":"%s","password":"%s"}""".formatted(email, password))
                .when()
                .post("/api/auth/login");

        if (response.statusCode() != 200) {
            throw new IllegalStateException("Login failed for %s: %d %s"
                    .formatted(email, response.statusCode(), response.asString()));
        }
        return response.asString().trim();
    }
}
