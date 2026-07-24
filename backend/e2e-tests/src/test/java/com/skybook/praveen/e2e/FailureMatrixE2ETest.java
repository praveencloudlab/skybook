package com.skybook.praveen.e2e;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Failure matrix (E2E_CERTIFICATION_MODULE.md §6, build-order step 6).
 *
 * <p>Proving the platform <b>fails correctly</b> is the half that usually goes
 * untested. Every trigger here is real and in-product - amounts ending
 * {@code .13} decline in the simulated gateway, and payment creation honours an
 * {@code Idempotency-Key} - so nothing needs a test-only backdoor.
 */
@DisplayName("Failure matrix: the platform fails correctly")
class FailureMatrixE2ETest {

    private static final String CURRENCY = "INR";

    @BeforeAll
    static void configureRestAssured() {
        RestAssured.baseURI = E2EConfig.BASE_URL;
    }

    // ---- declined payment ---------------------------------------------------

    @Test
    @DisplayName("a declined card leaves the payment failed, not silently captured")
    void declinedAuthorizationFails() {
        // .13 is the simulated gateway's deterministic decline trigger.
        Response created = createPaymentAsAdmin(new BigDecimal("100.13"), UUID.randomUUID().toString());
        assertThat(created.statusCode())
                .as("payment create failed: %s", created.asString())
                .isIn(200, 201);
        long paymentId = created.jsonPath().getLong("id");

        Response authorized = RestAssured.given()
                .header("Authorization", "Bearer " + Identities.adminToken())
                .when()
                .patch("/api/payments/" + paymentId + "/authorize");

        // A decline is surfaced to the caller as 422, not as a 200 carrying a
        // failed status - so the client cannot mistake it for success.
        assertThat(authorized.statusCode())
                .as("a declined card should be an explicit client error, got: %s", authorized.asString())
                .isEqualTo(422);

        assertThat(storedStatus(paymentId))
                .as("""
                        A declined authorization must be PERSISTED as failed. Anything that
                        leaves it AUTHORIZED would mean an unpaid booking could be confirmed.""")
                .isEqualTo("AUTHORIZATION_FAILED");

        Response afterFailure = RestAssured.given()
                .header("Authorization", "Bearer " + Identities.adminToken())
                .when()
                .patch("/api/payments/" + paymentId + "/capture");
        assertThat(afterFailure.statusCode())
                .as("capturing a payment that never authorized must be refused, not allowed")
                .isNotIn(200, 201);
    }

    // ---- duplicate request --------------------------------------------------

    @Test
    @DisplayName("replaying an Idempotency-Key returns the same payment, not a second charge")
    void idempotentPaymentCreation() {
        String key = UUID.randomUUID().toString();
        BigDecimal amount = new BigDecimal("250.00");

        Response first = createPaymentAsAdmin(amount, key);
        Response replay = createPaymentAsAdmin(amount, key);

        assertThat(first.jsonPath().getLong("id"))
                .as("""
                        The replay produced a DIFFERENT payment id - a retried request (flaky
                        network, impatient user, at-least-once delivery) would double-charge.""")
                .isEqualTo(replay.jsonPath().getLong("id"));
        assertThat(replay.jsonPath().getString("paymentReference"))
                .isEqualTo(first.jsonPath().getString("paymentReference"));
    }

    // ---- cancellation -------------------------------------------------------

    @Test
    @DisplayName("cancelling a paid booking cancels it and unwinds the payment")
    void cancellationUnwindsPayment() {
        E2EUser passenger = Identities.newUser("canceller");
        long bookingId = Journey.confirmedBooking(passenger);
        long paymentId = Journey.awaitPayment(passenger, bookingId).jsonPath().getLong("id");

        Response cancelled = RestAssured.given()
                .header("Authorization", passenger.bearer())
                .when()
                .patch("/api/bookings/" + bookingId + "/cancel");

        assertThat(cancelled.statusCode())
                .as("owner cancel failed: %s", cancelled.asString())
                .isEqualTo(200);
        assertThat(cancelled.jsonPath().getString("bookingStatus")).isEqualTo("CANCELLED");

        // payment-service reacts to BookingEvent CANCELLED asynchronously.
        await("payment for booking " + bookingId + " to leave CAPTURED after cancellation")
                .atMost(Journey.ASYNC_TIMEOUT)
                .pollInterval(java.time.Duration.ofSeconds(1))
                .until(() -> !"CAPTURED".equals(
                        paymentStatus(passenger, paymentId)));

        assertThat(paymentStatus(passenger, paymentId))
                .as("""
                        A cancelled booking must not leave money captured and unaccounted for -
                        the payment should land in a refunded/cancelled state.""")
                .isIn("REFUNDED", "PARTIALLY_REFUNDED", "CANCELLED");
    }

    // ---- authentication / authorization -------------------------------------

    @Test
    @DisplayName("no token reaches nothing")
    void unauthenticatedIsRejected() {
        assertThat(RestAssured.given().when().get("/api/bookings/1").statusCode())
                .isEqualTo(401);
        assertThat(RestAssured.given().when().get("/api/flights").statusCode())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("a passenger cannot mint payments or refunds")
    void passengerCannotUseAdminPaymentSurface() {
        E2EUser passenger = Identities.newUser("nopay");

        int create = RestAssured.given()
                .header("Authorization", passenger.bearer())
                .contentType("application/json")
                .body(paymentBody(new BigDecimal("10.00")))
                .when()
                .post("/api/payments")
                .statusCode();

        int refundList = RestAssured.given()
                .header("Authorization", passenger.bearer())
                .when()
                .get("/api/refunds")
                .statusCode();

        assertThat(create)
                .as("creating an arbitrary payment is a back-office action")
                .isEqualTo(403);
        assertThat(refundList)
                .as("the refund ledger is back-office; a passenger seeing it leaks other customers")
                .isEqualTo(403);
    }

    // ---- helpers ------------------------------------------------------------

    private Response createPaymentAsAdmin(BigDecimal amount, String idempotencyKey) {
        return RestAssured.given()
                .header("Authorization", "Bearer " + Identities.adminToken())
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .body(paymentBody(amount))
                .when()
                .post("/api/payments");
    }

    /**
     * Deliberately references a synthetic booking id: payments are idempotent by
     * bookingId, so a real booking already has its auto-created payment and could
     * not also carry a decline-triggering one.
     */
    private String paymentBody(BigDecimal amount) {
        return """
                {"bookingId":%d,"bookingReference":"E2E%s","amount":%s,"currency":"%s","method":"CARD"}"""
                .formatted(900_000_000L + (System.nanoTime() % 1_000_000),
                        E2EConfig.RUN_ID.substring(E2EConfig.RUN_ID.length() - 5),
                        amount.toPlainString(), CURRENCY);
    }

    /** Reads the persisted status as ADMIN (no owner on a synthetic booking). */
    private String storedStatus(long paymentId) {
        return RestAssured.given()
                .header("Authorization", "Bearer " + Identities.adminToken())
                .when()
                .get("/api/payments/" + paymentId)
                .jsonPath()
                .getString("status");
    }

    private String paymentStatus(E2EUser user, long paymentId) {
        return RestAssured.given()
                .header("Authorization", user.bearer())
                .when()
                .get("/api/payments/" + paymentId)
                .jsonPath()
                .getString("status");
    }
}
