package com.skybook.praveen.e2e;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Preflight (E2E_CERTIFICATION_MODULE.md §3, build-order step 1).
 *
 * <p>Runs before anything else and fails fast with a <b>specific remediation
 * message</b> for each precondition. A suite that dies with a bare 404 three
 * tests deep teaches nothing; this exists so the first failure tells you exactly
 * what to fix.
 *
 * <p>Everything is driven through the gateway only - since the security branch
 * that is the sole host-reachable port, and the correct trust boundary anyway.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Preflight: the environment is fit to certify against")
class PreflightE2ETest {

    private static String adminToken;

    @BeforeAll
    static void configureRestAssured() {
        RestAssured.baseURI = E2EConfig.BASE_URL;
        RestAssured.urlEncodingEnabled = true;
    }

    @Test
    @Order(1)
    @DisplayName("gateway is up")
    void gatewayIsUp() {
        Response response;
        try {
            response = RestAssured.given().when().get("/livez");
        } catch (Exception e) {
            fail("""
                    Cannot reach the API gateway at %s.
                    Remediation: start the fleet with `docker compose up -d`, then wait for
                    all containers to report healthy (`docker compose ps`).
                    Underlying error: %s""".formatted(E2EConfig.BASE_URL, e.getMessage()));
            return;
        }
        assertThat(response.statusCode())
                .as("gateway /livez should be 200 - the probe path is public on the main port")
                .isEqualTo(200);
    }

    @Test
    @Order(2)
    @DisplayName("an ADMIN account is configured and can log in")
    void adminIsUsable() {
        if (!E2EConfig.adminConfigured()) {
            fail("""
                    No ADMIN credentials supplied.
                    ADMIN cannot be granted through any API - auth-service only promotes the
                    address named by SKYBOOK_BOOTSTRAP_ADMIN_EMAIL, once, at startup.
                    Remediation:
                      1. set SKYBOOK_BOOTSTRAP_ADMIN_EMAIL=<your-admin@example.com> in .env
                      2. `docker compose up -d auth-service` to recreate it
                      3. register that email through the gateway (if it does not exist yet)
                      4. re-run with -De2e.admin.email=<...> -De2e.admin.password=<...>
                         (or E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD)""");
        }

        try {
            adminToken = Identities.adminToken();
        } catch (IllegalStateException e) {
            fail("""
                    %s
                    Remediation: register the address first (bootstrap only promotes an account
                    that already EXISTS), set SKYBOOK_BOOTSTRAP_ADMIN_EMAIL to it, then
                    `docker compose up -d auth-service`.""".formatted(e.getMessage()));
        }

        assertThat(Jwt.roles(adminToken))
                .as("the bootstrapped account must actually carry ROLE_ADMIN")
                .contains("ROLE_ADMIN");
    }

    @Test
    @Order(3)
    @DisplayName("every HTTP-facing service answers through the gateway")
    void servicesAreReachable() {
        // notification-service is deliberately absent: it is a pure Kafka consumer
        // with no business HTTP API, so it cannot be probed here. It gets certified
        // in build-order step 5 via a real captured email (MailHog).
        assertReachable("flight-service", "/api/flights/departure-date-range"
                + "?startDate=" + LocalDate.now() + "&endDate=" + LocalDate.now().plusDays(1));
        assertReachable("booking-service", "/api/bookings");
        assertReachable("inventory-service", "/api/aircraft");
        assertReachable("payment-service", "/api/refunds");
    }

    @Test
    @Order(4)
    @DisplayName("seed data contains a FUTURE flight")
    void futureFlightExists() {
        LocalDate from = LocalDate.now().plusDays(1);
        LocalDate to = LocalDate.now().plusDays(30);

        Response response = RestAssured.given()
                .header("Authorization", "Bearer " + adminToken)
                .when()
                .get("/api/flights/departure-date-range?startDate=" + from + "&endDate=" + to);

        assertThat(response.statusCode())
                .as("flight date-range query failed")
                .isEqualTo(200);

        List<Object> flights = response.jsonPath().getList("$");
        assertThat(flights)
                .as("""
                        No flights depart between %s and %s.
                        The whole journey needs a bookable future flight. Note the seed spans
                        only today..+365d, so an aged database passes a naive "any flights?"
                        check and still fails here.
                        Remediation: run `scripts/seed/seed.sh` (it uses docker exec, so it
                        works even though Postgres is no longer host-published)."""
                        .formatted(from, to))
                .isNotEmpty();
    }

    private void assertReachable(String service, String path) {
        Response response = RestAssured.given()
                .header("Authorization", "Bearer " + adminToken)
                .when()
                .get(path);

        // 502 is the gateway's own "downstream is not answering" signal.
        assertThat(response.statusCode())
                .withFailMessage("""
                        %s did not answer through the gateway (GET %s -> %d).
                        Remediation: `docker compose ps` and check that container is healthy;
                        `docker compose logs %s` for why it is not."""
                        .formatted(service, path, response.statusCode(), service))
                .isEqualTo(200);
    }

}
