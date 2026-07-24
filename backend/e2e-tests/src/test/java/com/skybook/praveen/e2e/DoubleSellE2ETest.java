package com.skybook.praveen.e2e;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The double-sell race (E2E_CERTIFICATION_MODULE.md §10.4, build-order step 9).
 *
 * <p>Two passengers go for the <b>same seat on the same flight at the same
 * instant</b>. Exactly one must win; the loser must get a clean conflict, not a
 * 500 and not a second confirmed booking for a seat that physically exists once.
 *
 * <p>This is the highest-value case in the matrix: the seat-locking added during
 * the PR #7 review has, until now, only ever been certified <em>by inspection</em>.
 * Nothing has actually run two writers at it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Concurrency: one seat cannot be sold twice")
class DoubleSellE2ETest {

    private E2EUser first;
    private E2EUser second;
    private long flightId;
    private String contestedSeat;

    @BeforeAll
    void setUp() {
        RestAssured.baseURI = E2EConfig.BASE_URL;
        first = Identities.newUser("racer-a");
        second = Identities.newUser("racer-b");

        // Must start from a flight with EVERY seat free, and it has to hold on a
        // re-run: this test consumes a seat, so simply taking "the first flight in
        // a window" worked once and then failed for ever after - neither racer
        // could win a seat the previous run had already taken. So: search for a
        // genuinely untouched flight rather than assuming one.
        flightId = untouchedFlight();
        contestedSeat = anEconomySeat(flightId);
    }

    @Test
    @DisplayName("simultaneous bookings of one seat: exactly one wins, cleanly")
    void onlyOneBookingWinsTheSeat() throws Exception {
        CountDownLatch startGun = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<Response> a = pool.submit(() -> {
                startGun.await();
                return Journey.createBooking(first, flightId, contestedSeat);
            });
            Future<Response> b = pool.submit(() -> {
                startGun.await();
                return Journey.createBooking(second, flightId, contestedSeat);
            });

            startGun.countDown();   // both threads are already parked on await()

            Response responseA = a.get(60, TimeUnit.SECONDS);
            Response responseB = b.get(60, TimeUnit.SECONDS);

            List<Integer> statuses = List.of(responseA.statusCode(), responseB.statusCode());

            assertThat(statuses.stream().filter(s -> s == 201).count())
                    .as("""
                            Seat %s on flight %d was requested twice at once and %d bookings
                            succeeded. Two winners means the seat was double-sold - two
                            passengers hold one physical seat. Responses: %s / %s""",
                            contestedSeat, flightId,
                            statuses.stream().filter(s -> s == 201).count(),
                            responseA.asString(), responseB.asString())
                    .isEqualTo(1);

            Response loser = responseA.statusCode() == 201 ? responseB : responseA;
            assertThat(loser.statusCode())
                    .as("""
                            The losing request must fail as a deliberate conflict the caller can
                            act on (pick another seat). A 500 would mean the race surfaced as an
                            unhandled error instead of a handled one. Body: %s""",
                            loser.asString())
                    .isEqualTo(409);
            assertThat(loser.asString())
                    .as("a conflict must not leak internals to the caller")
                    .doesNotContain("at com.skybook");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("after the race the seat is held exactly once")
    void seatIsHeldExactlyOnce() {
        // Whoever won, the flight must show exactly one seat consumed for it -
        // inventory counts are the system's own account of what it sold.
        Response inventory = RestAssured.given()
                .header("Authorization", "Bearer " + Identities.adminToken())
                .when()
                .get("/api/inventory/flight/" + flightId);

        assertThat(inventory.statusCode()).isEqualTo(200);

        int total = inventory.jsonPath().getInt("totalSeats");
        int available = inventory.jsonPath().getInt("availableSeats");
        int held = inventory.jsonPath().getInt("heldSeats");
        int reserved = inventory.jsonPath().getInt("reservedSeats");

        assertThat(available + held + reserved)
                .as("""
                        Inventory must still balance after a contested write: available + held
                        + reserved should account for every seat (%d). If it does not, the race
                        corrupted the counts even though the API looked correct.""", total)
                .isLessThanOrEqualTo(total);
        assertThat(held + reserved)
                .as("exactly one of the two racers should have consumed a seat")
                .isEqualTo(1);
    }

    /**
     * The first flight in a far-out window whose inventory is completely unsold.
     * Far out so ordinary journey tests (which book 2–20 days ahead) never touch
     * it, and unsold so both the race and the seat-count assertion start from a
     * known zero.
     */
    private long untouchedFlight() {
        List<Integer> candidates = RestAssured.given()
                .header("Authorization", "Bearer " + Identities.adminToken())
                .when()
                .get("/api/flights/departure-date-range?startDate="
                        + java.time.LocalDate.now().plusDays(25)
                        + "&endDate=" + java.time.LocalDate.now().plusDays(60))
                .jsonPath()
                .getList("id", Integer.class);

        for (Integer candidate : candidates) {
            Response inventory = RestAssured.given()
                    .header("Authorization", "Bearer " + Identities.adminToken())
                    .when()
                    .get("/api/inventory/flight/" + candidate);
            if (inventory.statusCode() != 200) {
                continue;
            }
            if (inventory.jsonPath().getInt("availableSeats")
                    == inventory.jsonPath().getInt("totalSeats")) {
                return candidate;
            }
        }
        throw new IllegalStateException("""
                No completely unsold flight between +25d and +60d.
                Every candidate already has seats held/reserved, so a contested-seat test
                cannot start from a known-zero state. Remediation: re-seed, or widen the
                window.""");
    }

    /** Any ECONOMY seat from the aircraft actually flying this flight. */
    private String anEconomySeat(long flight) {
        Response inventory = RestAssured.given()
                .header("Authorization", "Bearer " + Identities.adminToken())
                .when()
                .get("/api/inventory/flight/" + flight);
        long aircraftId = inventory.jsonPath().getLong("aircraftId");

        Response seatMap = RestAssured.given()
                .header("Authorization", "Bearer " + Identities.adminToken())
                .when()
                .get("/api/aircraft/" + aircraftId + "/seat-map");

        List<String> economySeats = seatMap.jsonPath()
                .getList("seats.findAll { it.seatType == 'ECONOMY' }.seatNumber", String.class);

        if (economySeats == null || economySeats.isEmpty()) {
            throw new IllegalStateException(
                    "aircraft " + aircraftId + " has no ECONOMY seats to contest");
        }
        // The last one: auto-assignment favours low-demand seats near the front,
        // so the back of the cabin is least likely to be taken by another test.
        return economySeats.get(economySeats.size() - 1);
    }
}
