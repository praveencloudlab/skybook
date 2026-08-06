package com.skybook.praveen.e2e;

import io.restassured.RestAssured;
import io.restassured.filter.cookie.CookieFilter;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guest check-in (GUEST_CHECKIN_MODULE.md): the agency scenario, end to end.
 *
 * <p>One account books - standing in for the travel company - and a passenger
 * who has NO account retrieves that booking with reference + surname, checks
 * in, holds a boarding pass, and has it emailed to an address they choose.
 *
 * <p>The other half of this class is the part that matters for security: the
 * same guest session is pushed at the money endpoints, at another booking,
 * and at services that never opted in. "Guest works where intended" and
 * "guest is refused everywhere else" are both tested claims here, not
 * configuration hopes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Guest check-in: no account, reference + surname, boarding pass")
class GuestCheckInE2ETest {

    /** The travel company that made the booking. The passenger never holds these credentials. */
    private E2EUser agency;
    private long bookingId;
    private String pnr;
    private long checkInId;

    /** Carries the __Host-skybook_guest cookie exactly as a browser would. */
    private CookieFilter guestSession;

    @BeforeAll
    void agencyBooksForThePassenger() {
        RestAssured.baseURI = E2EConfig.BASE_URL;
        agency = Identities.newUser("agency");
        bookingId = Journey.confirmedBooking(agency);
        pnr = Journey.getBooking(agency, bookingId).jsonPath().getString("bookingReference");
        Journey.awaitCheckIns(agency, bookingId);
        guestSession = new CookieFilter();
    }

    @Test
    @Order(1)
    @DisplayName("a wrong surname is refused with the same generic answer as an unknown reference")
    void mismatchesAreIndistinguishable() {
        Response wrongName = RestAssured.given().contentType("application/json")
                .body("{\"bookingReference\":\"" + pnr + "\",\"lastName\":\"Nobody\"}")
                .when().post("/api/bookings/guest-session");
        Response unknownRef = RestAssured.given().contentType("application/json")
                .body("{\"bookingReference\":\"ZZZZZZ\",\"lastName\":\"Passenger\"}")
                .when().post("/api/bookings/guest-session");

        assertThat(wrongName.statusCode()).isEqualTo(404);
        assertThat(unknownRef.statusCode())
                .as("nothing in the response may reveal whether the reference exists")
                .isEqualTo(wrongName.statusCode());
    }

    @Test
    @Order(2)
    @DisplayName("reference + surname opens a booking-scoped session")
    void guestRetrievesTheBooking() {
        Response session = RestAssured.given().filter(guestSession)
                .contentType("application/json")
                // Deliberately mis-cased and padded: the normalizer is part of
                // the contract, not a nicety.
                .body("{\"bookingReference\":\"" + pnr.toLowerCase() + "\",\"lastName\":\" passenger \"}")
                .when().post("/api/bookings/guest-session");

        assertThat(session.statusCode())
                .as("guest session refused: %s", session.asString())
                .isEqualTo(200);
        assertThat(session.jsonPath().getLong("bookingId")).isEqualTo(bookingId);

        Response booking = RestAssured.given().filter(guestSession)
                .when().get("/api/bookings/" + bookingId);
        assertThat(booking.statusCode()).isEqualTo(200);
        assertThat(booking.jsonPath().getString("bookingReference")).isEqualTo(pnr);
    }

    @Test
    @Order(3)
    @DisplayName("the guest checks in and receives a boarding pass")
    void guestChecksInAndGetsAPass() {
        Response records = RestAssured.given().filter(guestSession)
                .when().get("/api/checkins/booking/" + bookingId);
        assertThat(records.statusCode()).isEqualTo(200);
        checkInId = records.jsonPath().getLong("[0].id");

        Response checkedIn = RestAssured.given().filter(guestSession)
                .when().patch("/api/checkins/" + checkInId + "/checkin");
        assertThat(checkedIn.statusCode())
                .as("guest check-in failed: %s", checkedIn.asString())
                .isEqualTo(200);
        assertThat(checkedIn.jsonPath().getString("status")).isEqualTo("CHECKED_IN");

        Response pass = Journey.awaitResponse(
                () -> RestAssured.given().filter(guestSession)
                        .when().get("/api/boarding-passes/checkin/" + checkInId),
                "guest boarding pass for check-in " + checkInId);
        assertThat(pass.jsonPath().getString("boardingPassNumber")).isNotBlank();
        assertThat(pass.jsonPath().getString("token")).isNotBlank();
    }

    @Test
    @Order(4)
    @DisplayName("[async] the pass is emailed to the address the guest chose")
    void guestEmailsThePassToTheirOwnAddress() {
        String chosen = "guest-" + System.nanoTime() + "@example.test";

        Response accepted = RestAssured.given().filter(guestSession)
                .contentType("application/json")
                .body("{\"email\":\"" + chosen + "\"}")
                .when().post("/api/boarding-passes/checkin/" + checkInId + "/email");
        assertThat(accepted.statusCode())
                .as("email request refused: %s", accepted.asString())
                .isEqualTo(202);

        // The address is the passenger's, NOT the agency's - the whole point
        // of the feature. Delivery rides the existing QR+PDF pipeline.
        Journey.awaitResponse(
                () -> {
                    Response inbox = RestAssured.given().baseUri(E2EConfig.MAIL_URL)
                            .when().get("/api/v1/search?query=" + chosen);
                    return inbox.jsonPath().getInt("messages_count") > 0 ? inbox : null;
                },
                "boarding-pass email to " + chosen);
    }

    @Test
    @Order(5)
    @DisplayName("the guest session is refused on every money endpoint")
    void theCageHolds() {
        int cancel = RestAssured.given().filter(guestSession)
                .when().delete("/api/bookings/" + bookingId).statusCode();
        int listAll = RestAssured.given().filter(guestSession)
                .when().get("/api/bookings").statusCode();
        int mine = RestAssured.given().filter(guestSession)
                .when().get("/api/bookings/mine").statusCode();

        assertThat(cancel)
                .as("cancelling is the owning account's business, never a guest's")
                .isIn(403, 405);
        assertThat(listAll).isEqualTo(403);
        assertThat(mine).isEqualTo(403);
    }

    @Test
    @Order(6)
    @DisplayName("another booking does not exist for this guest - 404, never 403")
    void otherBookingsAreConcealed() {
        E2EUser stranger = Identities.newUser("stranger");
        long otherBookingId = Journey.confirmedBooking(stranger);

        int status = RestAssured.given().filter(guestSession)
                .when().get("/api/bookings/" + otherBookingId).statusCode();

        assertThat(status)
                .as("a 403/404 split would be an existence oracle for bookings")
                .isEqualTo(404);
    }

    @Test
    @Order(7)
    @DisplayName("services that never opted in reject the guest token outright")
    void servicesWithoutTheOptInRefuseGuests() {
        // accept-guest-tokens defaults FALSE: a guest credential must die at
        // the validator in flight/inventory/payment, not merely be unmapped.
        int payments = RestAssured.given().filter(guestSession)
                .when().get("/api/payments/booking/" + bookingId).statusCode();
        int reservations = RestAssured.given().filter(guestSession)
                .when().get("/api/reservations/flight/1").statusCode();

        assertThat(payments)
                .as("payment-service never opted in to guest tokens")
                .isIn(401, 403);
        assertThat(reservations)
                .as("inventory-service never opted in to guest tokens")
                .isIn(401, 403);
    }

    @Test
    @Order(8)
    @DisplayName("ending the session closes the door behind it")
    void doneMeansDone() {
        int ended = RestAssured.given().filter(guestSession)
                .when().delete("/api/bookings/guest-session").statusCode();
        assertThat(ended).isEqualTo(204);

        int afterwards = RestAssured.given().filter(guestSession)
                .when().get("/api/bookings/" + bookingId).statusCode();
        assertThat(afterwards)
                .as("the cookie was expired, so the guest is anonymous again")
                .isEqualTo(401);
    }
}
