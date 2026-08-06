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
    /** The same token, captured at issuance, for the explicit-credential arm of the cage test. */
    private String guestToken;

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

        String setCookie = session.getHeaders().getValues("Set-Cookie").stream()
                .filter(h -> h.startsWith("__Host-skybook_guest=") || h.startsWith("skybook_guest="))
                .findFirst().orElseThrow(() -> new AssertionError("no guest cookie was set"));
        guestToken = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));

        // The invariants that hold in EVERY environment. Secure is
        // deliberately absent under the e2e overlay and only there (an HTTP
        // client will not send a Secure cookie over plain HTTP, which would
        // make this whole journey untestable); everywhere a browser is
        // involved it is on, which is what the __Host- prefix requires.
        assertThat(setCookie)
                .as("the guest credential is never readable by script, and never scoped to a parent domain")
                .contains("HttpOnly").contains("Path=/").doesNotContain("Domain=");
        assertThat(guestToken).isNotBlank();

        // Distinct from the account session, which is the whole point: a
        // signed-in agency looking up a guest booking must not be logged out.
        assertThat(setCookie).doesNotContain("skybook_session=");

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
    @DisplayName("the money endpoints refuse the guest at BOTH layers")
    void theCageHolds() {
        // Layer 1 - the gateway. Outside the guest-capable path list the guest
        // cookie is not even consulted, so the request arrives anonymous and
        // is refused at the edge. Verified live: 401, never a 200.
        int mineByCookie = RestAssured.given().filter(guestSession)
                .when().get("/api/bookings/mine").statusCode();
        int listByCookie = RestAssured.given().filter(guestSession)
                .when().get("/api/bookings").statusCode();

        assertThat(mineByCookie).isIn(401, 403);
        assertThat(listByCookie).isIn(401, 403);

        // Layer 2 - the service cage, which is what actually holds if the
        // token is presented explicitly rather than ambiently. This is the
        // arm that would catch a future path wrongly added to the
        // guest-capable list: booking-service ends its chain in
        // hasAnyRole(USER, ADMIN), so ROLE_GUEST is refused by default.
        assertThat(guestToken).as("captured at issuance").isNotBlank();

        int mineByBearer = RestAssured.given().header("Authorization", "Bearer " + guestToken)
                .when().get("/api/bookings/mine").statusCode();
        int listByBearer = RestAssured.given().header("Authorization", "Bearer " + guestToken)
                .when().get("/api/bookings").statusCode();
        int cancelByBearer = RestAssured.given().header("Authorization", "Bearer " + guestToken)
                .when().delete("/api/bookings/" + bookingId).statusCode();

        assertThat(mineByBearer)
                .as("a guest token is not a USER token, whatever it rides in on")
                .isEqualTo(403);
        assertThat(listByBearer).isEqualTo(403);
        assertThat(cancelByBearer)
                .as("cancelling is the owning account's business, never a guest's")
                .isIn(403, 405);
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
        // Not a hollow assertion: the same paths answer 200 to the OWNING
        // account, so these refusals are about the credential, not the route.
        assertThat(RestAssured.given().header("Authorization", agency.bearer())
                .when().get("/api/payments/booking/" + bookingId).statusCode())
                .as("the owner can see their own payment - the refusal above is the guest's")
                .isEqualTo(200);
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
