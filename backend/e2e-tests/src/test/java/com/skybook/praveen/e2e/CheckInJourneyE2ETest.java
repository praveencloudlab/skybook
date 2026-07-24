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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Check-in → boarding pass → boarded (build-order step 4), completing the
 * journey the happy path left at CONFIRMED.
 *
 * <p>This half of the journey is split across two <b>different</b> actors, which
 * is the point: the passenger checks themselves in and holds a boarding pass,
 * but only a gate agent (ADMIN) may actually board them. A passenger who could
 * board themselves would be a real authorization defect.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Check-in: passenger checks in, gate boards them")
class CheckInJourneyE2ETest {

    private E2EUser passenger;
    private long bookingId;
    private String pnr;
    private long checkInId;

    @BeforeAll
    void bookAndPay() {
        RestAssured.baseURI = E2EConfig.BASE_URL;
        passenger = Identities.newUser("checkin");
        bookingId = Journey.confirmedBooking(passenger);
        pnr = Journey.getBooking(passenger, bookingId).jsonPath().getString("bookingReference");
    }

    @Test
    @Order(1)
    @DisplayName("[async] check-in records appear from the confirmed-booking event")
    void checkInRecordIsCreated() {
        Response response = Journey.awaitCheckIns(passenger, bookingId);

        assertThat(response.jsonPath().getList("$"))
                .as("checkin-service creates one record per passenger off BookingEvent CONFIRMED")
                .hasSize(1);

        checkInId = response.jsonPath().getLong("[0].id");
        assertThat(response.jsonPath().getString("[0].bookingReference")).isEqualTo(pnr);
        assertThat(response.jsonPath().getString("[0].status"))
                .as("a freshly created record is not yet checked in")
                .isIn("NOT_OPEN", "OPEN");
    }

    @Test
    @Order(2)
    @DisplayName("another passenger cannot check in someone else's booking")
    void otherPassengerCannotCheckIn() {
        E2EUser intruder = Identities.newUser("gatecrasher");

        int status = RestAssured.given()
                .header("Authorization", intruder.bearer())
                .when()
                .patch("/api/checkins/" + checkInId + "/checkin")
                .statusCode();

        assertThat(status)
                .as("check-in is passenger self-service, guarded by ownership at the controller")
                .isEqualTo(403);
    }

    @Test
    @Order(3)
    @DisplayName("the passenger checks themselves in")
    void passengerChecksIn() {
        Response response = RestAssured.given()
                .header("Authorization", passenger.bearer())
                .when()
                .patch("/api/checkins/" + checkInId + "/checkin");

        assertThat(response.statusCode())
                .as("check-in failed: %s", response.asString())
                .isEqualTo(200);
        assertThat(response.jsonPath().getString("status")).isEqualTo("CHECKED_IN");
        assertThat(response.jsonPath().getString("seatNumber"))
                .as("the seat held at booking should still be the seat checked in against")
                .isNotBlank();
    }

    @Test
    @Order(4)
    @DisplayName("a boarding pass is issued to the passenger, signed")
    void boardingPassIsIssued() {
        Response response = Journey.awaitResponse(
                () -> RestAssured.given()
                        .header("Authorization", passenger.bearer())
                        .when()
                        .get("/api/boarding-passes/checkin/" + checkInId),
                "boarding pass for check-in " + checkInId);

        assertThat(response.jsonPath().getString("boardingPassNumber")).isNotBlank();
        assertThat(response.jsonPath().getString("bookingReference")).isEqualTo(pnr);
        assertThat(response.jsonPath().getString("seatNumber")).isNotBlank();
        assertThat(response.jsonPath().getString("token"))
                .as("""
                        The pass carries a signed token - that signature is the only thing
                        stopping a forged boarding pass, so an unsigned pass is a real defect.""")
                .isNotBlank();
    }

    @Test
    @Order(5)
    @DisplayName("a passenger may NOT board themselves - that is the gate's job")
    void passengerCannotSelfBoard() {
        int status = RestAssured.given()
                .header("Authorization", passenger.bearer())
                .when()
                .patch("/api/checkins/" + checkInId + "/board")
                .statusCode();

        assertThat(status)
                .as("boarding is a gate (ADMIN) operation; self-boarding would defeat the gate")
                .isEqualTo(403);
    }

    @Test
    @Order(6)
    @DisplayName("the gate boards the passenger")
    void gateBoardsPassenger() {
        Response response = RestAssured.given()
                .header("Authorization", "Bearer " + Identities.adminToken())
                .when()
                .patch("/api/checkins/" + checkInId + "/board");

        assertThat(response.statusCode())
                .as("boarding failed: %s", response.asString())
                .isEqualTo(200);
        assertThat(response.jsonPath().getString("status")).isEqualTo("BOARDED");
    }
}
