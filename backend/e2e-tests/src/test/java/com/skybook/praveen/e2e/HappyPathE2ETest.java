package com.skybook.praveen.e2e;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The happy-path spine (E2E_CERTIFICATION_MODULE.md §5, build-order step 3):
 * search → quote → book → pay → confirmed.
 *
 * <p>Written as one ordered narrative rather than independent tests, because it
 * <em>is</em> one journey - a booking cannot be paid for before it exists. Each
 * step asserts both the response <b>and the state change it implies</b>, which
 * is where the async (Kafka) hops get certified.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Happy path: a passenger books and pays for a seat")
class HappyPathE2ETest {

    private E2EUser passenger;
    private long flightId;
    private long bookingId;
    private String pnr;
    private BigDecimal totalFare;
    private long paymentId;

    @BeforeAll
    void setUp() {
        RestAssured.baseURI = E2EConfig.BASE_URL;
        passenger = Identities.newUser("happy");
        flightId = Journey.futureFlightId(passenger.token());
    }

    @Test
    @Order(1)
    @DisplayName("quote shows what the flight sells before committing to anything")
    void quoteFares() {
        Response response = RestAssured.given()
                .header("Authorization", passenger.bearer())
                .contentType("application/json")
                .body("{\"flightId\":%d}".formatted(flightId))
                .when()
                .post("/api/bookings/quote");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getLong("flightId")).isEqualTo(flightId);
        assertThat(response.jsonPath().getList("cabins"))
                .as("a seeded flight must sell at least one cabin, else nothing downstream can work")
                .isNotEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("booking is CREATED, owned by the caller, with a seat actually held")
    void createBooking() {
        Response response = Journey.createBooking(passenger, flightId);

        assertThat(response.statusCode())
                .as("booking create failed: %s", response.asString())
                .isEqualTo(201);

        bookingId = response.jsonPath().getLong("id");
        pnr = response.jsonPath().getString("bookingReference");
        totalFare = new BigDecimal(response.jsonPath().getString("totalFare"));

        assertThat(pnr).as("a PNR is the passenger's handle on the booking").isNotBlank();
        assertThat(response.jsonPath().getString("bookingStatus"))
                .as("""
                        POST /api/bookings runs draft -> hold -> finalize internally, so a
                        successful create lands on CREATED. A DRAFT here means finalize failed
                        and the seat hold was never committed.""")
                .isEqualTo("CREATED");

        assertThat(response.jsonPath().getString("ownerSubject"))
                .as("ownerSubject is captured from the token - it is what every later OWNER check compares")
                .isEqualTo(passenger.subject());

        assertThat(response.jsonPath().getString("passengers[0].seatNumber"))
                .as("seat auto-assignment should have held a real seat")
                .isNotBlank();
        assertThat(totalFare)
                .as("a booked fare must be a positive amount")
                .isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @Order(3)
    @DisplayName("[async] payment-service auto-creates a PENDING payment from the booking event")
    void paymentIsAutoCreated() {
        Response response = Journey.awaitPayment(passenger, bookingId);

        paymentId = response.jsonPath().getLong("id");
        assertThat(response.jsonPath().getString("status")).isEqualTo("PENDING");
        assertThat(new BigDecimal(response.jsonPath().getString("amount")))
                .as("the payment must bill exactly the booking's total fare")
                .isEqualByComparingTo(totalFare);
        assertThat(response.jsonPath().getString("bookingReference")).isEqualTo(pnr);
    }

    @Test
    @Order(4)
    @DisplayName("authorize then capture takes the payment to CAPTURED")
    void authorizeAndCapture() {
        Response authorized = Journey.authorize(passenger, paymentId);
        assertThat(authorized.statusCode())
                .as("authorize failed: %s", authorized.asString())
                .isEqualTo(200);
        assertThat(authorized.jsonPath().getString("status")).isEqualTo("AUTHORIZED");

        Response captured = Journey.capture(passenger, paymentId);
        assertThat(captured.statusCode())
                .as("capture failed: %s", captured.asString())
                .isEqualTo(200);
        assertThat(captured.jsonPath().getString("status")).isEqualTo("CAPTURED");
        assertThat(new BigDecimal(captured.jsonPath().getString("capturedAmount")))
                .isEqualByComparingTo(totalFare);
    }

    @Test
    @Order(5)
    @DisplayName("[async] booking-service confirms the booking off PAYMENT_SUCCEEDED")
    void bookingBecomesConfirmed() {
        Journey.awaitBookingStatus(passenger, bookingId, "CONFIRMED");

        Response booking = Journey.getBooking(passenger, bookingId);
        assertThat(booking.jsonPath().getString("bookingStatus")).isEqualTo("CONFIRMED");
        assertThat(booking.jsonPath().getString("bookingReference")).isEqualTo(pnr);
    }

    @Test
    @Order(6)
    @DisplayName("the confirmed booking is retrievable by PNR, and only by its owner")
    void ownerCanReadByPnrAndOthersCannot() {
        Response byPnr = RestAssured.given()
                .header("Authorization", passenger.bearer())
                .when()
                .get("/api/bookings/reference/" + pnr);
        assertThat(byPnr.statusCode()).isEqualTo(200);
        assertThat(byPnr.jsonPath().getLong("id")).isEqualTo(bookingId);

        E2EUser intruder = Identities.newUser("intruder");
        Map<String, Integer> attempts = Map.of(
                "/api/bookings/" + bookingId,
                RestAssured.given().header("Authorization", intruder.bearer())
                        .when().get("/api/bookings/" + bookingId).statusCode(),
                "/api/bookings/reference/" + pnr,
                RestAssured.given().header("Authorization", intruder.bearer())
                        .when().get("/api/bookings/reference/" + pnr).statusCode());

        assertThat(attempts.values())
                .as("a different passenger must not reach this booking by id OR by PNR - "
                        + "guessing a PNR must not be a way around ownership")
                .containsOnly(403);
    }
}
