package com.skybook.praveen.e2e;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Degradation and recovery (E2E_CERTIFICATION_MODULE.md §6, build-order step 8).
 *
 * <p>Kills inventory-service mid-flight and asserts the platform degrades
 * <b>cleanly</b>: a caller gets a deliberate 502, not a hung request or a 500
 * leaking a stack trace, and the system recovers by itself once the dependency
 * returns. This is the case most likely to expose a real defect and the one no
 * unit test can reach.
 *
 * <p>Restarting inventory in {@code @AfterAll} is not tidiness - if this class
 * failed midway and left it stopped, every later test in the run would fail for
 * an unrelated reason and the real cause would be buried.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Service down: degrade cleanly, then recover")
class ServiceDownE2ETest {

    private static final String INVENTORY = "inventory-service";

    private E2EUser passenger;
    private long flightId;

    @BeforeAll
    void setUp() {
        RestAssured.baseURI = E2EConfig.BASE_URL;
        passenger = Identities.newUser("degraded");
        flightId = Journey.futureFlightId(passenger.token());
    }

    @AfterAll
    void restoreInventory() {
        Compose.start(INVENTORY);
        Compose.awaitHealthy(INVENTORY, Duration.ofMinutes(2));
    }

    @Test
    @Order(1)
    @DisplayName("booking fails with a clean 502 while inventory is down")
    void bookingDegradesTo502() {
        Compose.stop(INVENTORY);

        Response response = Journey.createBooking(passenger, flightId);

        assertThat(response.statusCode())
                .as("""
                        With inventory down, booking must fail as a deliberate 502 Bad Gateway.
                        A 500 would mean the failure escaped as an unhandled error, and a hang
                        would mean the Feign timeout is not doing its job. Got: %s""",
                        response.asString())
                .isEqualTo(502);

        assertThat(response.asString())
                .as("an error response must not leak a stack trace to the caller")
                .doesNotContain("Exception", "at com.skybook");
    }

    @Test
    @Order(2)
    @DisplayName("unrelated reads still work - the blast radius is contained")
    void unrelatedReadsSurvive() {
        int flights = RestAssured.given()
                .header("Authorization", passenger.bearer())
                .when()
                .get("/api/flights/departure-date-range?startDate=" + java.time.LocalDate.now()
                        + "&endDate=" + java.time.LocalDate.now().plusDays(2))
                .statusCode();

        assertThat(flights)
                .as("""
                        flight-service does not depend on inventory, so it must stay up. If this
                        fails, one dead service is taking the fleet with it.""")
                .isEqualTo(200);
    }

    @Test
    @Order(3)
    @DisplayName("the platform recovers on its own once inventory returns")
    void recoversAfterRestart() {
        Compose.start(INVENTORY);
        Compose.awaitHealthy(INVENTORY, Duration.ofMinutes(2));

        // The circuit breaker needs its wait-duration to elapse before it will
        // try again, so recovery is polled rather than asserted immediately.
        Response recovered = Journey.awaitStatus(
                () -> Journey.createBooking(passenger, flightId),
                201,
                "booking to succeed again after inventory recovered "
                        + "(the circuit breaker must close, not stay open)");

        assertThat(recovered.jsonPath().getString("bookingStatus"))
                .as("recovery should need no human intervention")
                .isEqualTo("CREATED");
    }
}
