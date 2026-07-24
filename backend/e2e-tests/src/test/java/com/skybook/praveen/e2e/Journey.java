package com.skybook.praveen.e2e;

import io.restassured.RestAssured;
import io.restassured.response.Response;

import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.Callable;

import static org.awaitility.Awaitility.await;

/**
 * Shared steps of the customer journey (E2E_CERTIFICATION_MODULE.md §5).
 *
 * <p>Kept separate from the tests so the failure-matrix cases can reuse the
 * setup ("get me a confirmed booking") without duplicating it.
 *
 * <p><b>Async effects are polled, never slept on.</b> Several hops here are
 * Kafka-driven (booking CREATED → payment auto-created; payment captured →
 * booking CONFIRMED), so the only honest wait is "retry until it's true, or fail
 * loudly". Timeouts are deliberately generous - CI runners are slow, and the
 * resilience branch already learned that 20s was too tight where 40s was fine.
 */
public final class Journey {

    /** Generous on purpose: a tight bound here produces flakes, not signal. */
    public static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration POLL = Duration.ofMillis(500);

    private Journey() {
    }

    /**
     * A flight far enough out that check-in is still closed (the window opens 24h
     * before departure), so the journey controls when check-in becomes valid.
     */
    public static long futureFlightId(String token) {
        LocalDate from = LocalDate.now().plusDays(2);
        LocalDate to = LocalDate.now().plusDays(20);

        Response response = RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/flights/departure-date-range?startDate=" + from + "&endDate=" + to);

        require(response.statusCode() == 200,
                "flight lookup failed: " + response.statusCode() + " " + response.asString());

        Integer id = response.jsonPath().get("[0].id");
        require(id != null, """
                No flights between %s and %s.
                Remediation: run `scripts/seed/seed.sh` (the seed spans only today..+365d).""");
        return id;
    }

    /** Books one ECONOMY/SAVER passenger with an auto-assigned (free) seat. */
    public static Response createBooking(E2EUser user, long flightId) {
        String passenger = """
                {"title":"Mr","firstName":"E2E","lastName":"Passenger","dob":"1990-01-01",
                 "nationality":"IND","passportNumber":"P%s","passportExpiry":"2032-01-01",
                 "travelClass":"ECONOMY","fareType":"SAVER"}"""
                .formatted(E2EConfig.RUN_ID.substring(E2EConfig.RUN_ID.length() - 6));

        return RestAssured.given()
                .header("Authorization", user.bearer())
                .contentType("application/json")
                .body("""
                        {"customerId":9001,"flightId":%d,"passengers":[%s],
                         "contact":{"contactName":"E2E Passenger","contactEmail":"%s"}}"""
                        .formatted(flightId, passenger, user.email()))
                .when()
                .post("/api/bookings");
    }

    /**
     * The payment row is created by payment-service's Kafka consumer reacting to
     * BookingEvent CREATED - so it does not exist the instant the booking POST
     * returns. Polls until it does.
     */
    public static Response awaitPayment(E2EUser user, long bookingId) {
        return awaitResponse(
                () -> RestAssured.given()
                        .header("Authorization", user.bearer())
                        .when()
                        .get("/api/payments/booking/" + bookingId),
                "payment auto-created for booking " + bookingId
                        + " (payment-service consumes BookingEvent CREATED)");
    }

    public static Response authorize(E2EUser user, long paymentId) {
        return RestAssured.given()
                .header("Authorization", user.bearer())
                .when()
                .patch("/api/payments/" + paymentId + "/authorize");
    }

    public static Response capture(E2EUser user, long paymentId) {
        return RestAssured.given()
                .header("Authorization", user.bearer())
                .when()
                .patch("/api/payments/" + paymentId + "/capture");
    }

    public static Response getBooking(E2EUser user, long bookingId) {
        return RestAssured.given()
                .header("Authorization", user.bearer())
                .when()
                .get("/api/bookings/" + bookingId);
    }

    /** Waits for booking-service to consume PAYMENT_SUCCEEDED and confirm. */
    public static void awaitBookingStatus(E2EUser user, long bookingId, String expected) {
        await("booking " + bookingId + " reaching " + expected
                        + " (booking-service consumes PAYMENT_SUCCEEDED)")
                .atMost(ASYNC_TIMEOUT)
                .pollInterval(POLL)
                .until(() -> expected.equals(
                        getBooking(user, bookingId).jsonPath().getString("bookingStatus")));
    }

    /** Polls a call until it returns 200. */
    public static Response awaitResponse(Callable<Response> call, String what) {
        Response[] last = new Response[1];
        await(what)
                .atMost(ASYNC_TIMEOUT)
                .pollInterval(POLL)
                .until(() -> {
                    last[0] = call.call();
                    return last[0].statusCode() == 200;
                });
        return last[0];
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
