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

        // A replay is the SAME request sent twice - so the body must be
        // identical, built ONCE. paymentBody() mints a fresh synthetic
        // bookingId per call (nanoTime), which would make these two genuinely
        // DIFFERENT requests; the server now (correctly) rejects a key reused
        // with a different body as a 409 (IDEMPOTENCY_MODULE.md §3.2), so
        // reusing a single body is what actually exercises replay.
        String body = paymentBody(new BigDecimal("250.00"));

        Response first = createPaymentAsAdmin(body, key);
        Response replay = createPaymentAsAdmin(body, key);

        assertThat(first.jsonPath().getLong("id"))
                .as("""
                        The replay produced a DIFFERENT payment id - a retried request (flaky
                        network, impatient user, at-least-once delivery) would double-charge.""")
                .isEqualTo(replay.jsonPath().getLong("id"));
        assertThat(replay.jsonPath().getString("paymentReference"))
                .isEqualTo(first.jsonPath().getString("paymentReference"));

        // The other half of §3.2: the SAME key with a DIFFERENT body is a
        // client bug, and must be refused rather than silently answered with
        // the first payment. paymentBody() mints a new bookingId, so this is a
        // genuinely different request under the same key.
        int mismatch = createPaymentAsAdmin(new BigDecimal("250.00"), key).statusCode();
        assertThat(mismatch)
                .as("a key reused for a different request must 409, not replay someone else's payment")
                .isEqualTo(409);
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
    @DisplayName("no token reaches nothing PRIVATE - but shopping stays open")
    void unauthenticatedIsRejected() {
        // Anything belonging to a person needs a credential.
        assertThat(RestAssured.given().when().get("/api/bookings/1").statusCode())
                .as("someone else's booking must never answer an anonymous caller")
                .isEqualTo(401);
        assertThat(RestAssured.given().when().get("/api/bookings/mine").statusCode())
                .as("'my' anything is meaningless without a token")
                .isEqualTo(401);

        // Shopping data is deliberately public: a visitor searches and prices
        // a trip before any account exists, the way every travel site works
        // (the gateway's PUBLIC_PATHS carries /api/flights/**). This assertion
        // used to demand 401 here, which quietly became wrong the day public
        // search shipped - and left this suite red for four nights.
        //
        // SEARCH, not the bare list. /api/flights is an unpaginated list-ALL
        // that answers 200 on a laptop's dataset and 401 on production's
        // ~400k rows - a masked failure, not an authorization decision (the
        // body carries "path":"/error", Spring's error forward, which is
        // itself behind auth). Asserting it here made this test a scale probe
        // wearing a security test's clothes. The search endpoint is what the
        // frontend actually calls to shop, so it is the honest subject.
        assertThat(RestAssured.given()
                .queryParam("originAirportCode", "LHR")
                .queryParam("destinationAirportCode", "DXB")
                .queryParam("departureDate", java.time.LocalDate.now().plusDays(14).toString())
                .when().get("/api/flights/search").statusCode())
                .as("public shopping data must remain reachable without an account")
                .isEqualTo(200);
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
        return createPaymentAsAdmin(paymentBody(amount), idempotencyKey);
    }

    /** Overload taking a PRE-BUILT body, so a replay can resend the identical request. */
    private Response createPaymentAsAdmin(String body, String idempotencyKey) {
        return RestAssured.given()
                .header("Authorization", "Bearer " + Identities.adminToken())
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .body(body)
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
