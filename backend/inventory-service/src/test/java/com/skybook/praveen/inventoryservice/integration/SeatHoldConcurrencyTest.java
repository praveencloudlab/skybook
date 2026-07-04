package com.skybook.praveen.inventoryservice.integration;

import com.skybook.praveen.inventoryservice.dto.request.HoldSeatRequest;
import com.skybook.praveen.inventoryservice.entity.Aircraft;
import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import com.skybook.praveen.inventoryservice.entity.FlightInventory;
import com.skybook.praveen.inventoryservice.enums.SeatHoldStatus;
import com.skybook.praveen.inventoryservice.enums.SeatPosition;
import com.skybook.praveen.inventoryservice.enums.SeatType;
import com.skybook.praveen.inventoryservice.exception.SeatAlreadyHeldException;
import com.skybook.praveen.inventoryservice.repository.AircraftRepository;
import com.skybook.praveen.inventoryservice.repository.AircraftSeatRepository;
import com.skybook.praveen.inventoryservice.repository.FlightInventoryRepository;
import com.skybook.praveen.inventoryservice.repository.InventoryHistoryRepository;
import com.skybook.praveen.inventoryservice.repository.SeatHoldRepository;
import com.skybook.praveen.inventoryservice.repository.SeatReservationRepository;
import com.skybook.praveen.inventoryservice.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the concurrency claim from INVENTORY_SERVICE_MODULE.md section 6:
 * "one ACTIVE hold per seat" has no DB constraint, but racing holds collide
 * on FlightInventory's @Version because every hold mutates the counts.
 *
 * Real PostgreSQL, real transactions, real threads - each holdSeat call
 * runs in its own transaction (this test class is deliberately NOT
 * @Transactional).
 */
class SeatHoldConcurrencyTest extends AbstractPostgresSpringBootTest {

    private static final AtomicLong FLIGHT_SEQ = new AtomicLong(9000);

    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private AircraftRepository aircraftRepository;
    @Autowired
    private AircraftSeatRepository aircraftSeatRepository;
    @Autowired
    private FlightInventoryRepository flightInventoryRepository;
    @Autowired
    private SeatHoldRepository seatHoldRepository;
    @Autowired
    private SeatReservationRepository seatReservationRepository;
    @Autowired
    private InventoryHistoryRepository inventoryHistoryRepository;

    private Long flightId;
    private Long inventoryId;

    @BeforeEach
    void setUp() {
        seatReservationRepository.deleteAll();
        seatHoldRepository.deleteAll();
        inventoryHistoryRepository.deleteAll();
        flightInventoryRepository.deleteAll();
        aircraftSeatRepository.deleteAll();
        aircraftRepository.deleteAll();

        Aircraft aircraft = aircraftRepository.save(Aircraft.builder()
                .registrationNumber("VT-CON").manufacturer("Airbus").model("A320neo").totalSeats(3).build());

        for (String seatNumber : List.of("12A", "12B", "12C")) {
            aircraftSeatRepository.save(AircraftSeat.builder()
                    .aircraft(aircraft).seatNumber(seatNumber).rowNumber(12)
                    .seatType(SeatType.ECONOMY).position(SeatPosition.WINDOW).build());
        }

        flightId = FLIGHT_SEQ.incrementAndGet();
        FlightInventory inventory = flightInventoryRepository.save(FlightInventory.builder()
                .flightId(flightId).aircraft(aircraft)
                .totalSeats(3).availableSeats(3).heldSeats(0).reservedSeats(0).blockedSeats(0)
                .build());
        inventoryId = inventory.getId();
    }

    private List<Throwable> race(List<Callable<Object>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();

        for (Callable<Object> call : calls) {
            futures.add(pool.submit(() -> {
                start.await();
                return call.call();
            }));
        }

        start.countDown();

        List<Throwable> failures = new ArrayList<>();
        for (Future<Object> future : futures) {
            try {
                future.get();
            } catch (java.util.concurrent.ExecutionException e) {
                failures.add(e.getCause());
            }
        }
        pool.shutdown();
        return failures;
    }

    @Test
    void twoBookingsRacingForTheSameSeatYieldExactlyOneHold() throws Exception {

        List<Throwable> failures = race(List.of(
                () -> inventoryService.holdSeat(new HoldSeatRequest(flightId, "12A", 111L)),
                () -> inventoryService.holdSeat(new HoldSeatRequest(flightId, "12A", 222L))
        ));

        // Exactly one winner.
        assertThat(failures).hasSize(1);

        // The loser failed for one of the two legitimate reasons: it saw the
        // winner's hold (serial timing) or collided on @Version (true race).
        assertThat(failures.getFirst())
                .isInstanceOfAny(SeatAlreadyHeldException.class, OptimisticLockingFailureException.class);

        // Exactly one ACTIVE hold row exists for the seat.
        assertThat(seatHoldRepository.findAll())
                .filteredOn(h -> h.getStatus() == SeatHoldStatus.ACTIVE)
                .hasSize(1);

        // Counts reflect exactly one hold and the invariant holds.
        FlightInventory inventory = flightInventoryRepository.findById(inventoryId).orElseThrow();
        assertThat(inventory.getAvailableSeats()).isEqualTo(2);
        assertThat(inventory.getHeldSeats()).isEqualTo(1);
        assertThat(inventory.getAvailableSeats() + inventory.getHeldSeats()
                + inventory.getReservedSeats() + inventory.getBlockedSeats())
                .isEqualTo(inventory.getTotalSeats());
    }

    @Test
    void racesOnDifferentSeatsNeverCorruptTheCounts() throws Exception {

        // Three bookings, three different seats, same inventory row. Without
        // retry logic some may lose the optimistic lock (documented 409
        // behavior) - what must NEVER happen is a successful hold that isn't
        // reflected in the counts.
        List<Throwable> failures = race(List.of(
                () -> inventoryService.holdSeat(new HoldSeatRequest(flightId, "12A", 111L)),
                () -> inventoryService.holdSeat(new HoldSeatRequest(flightId, "12B", 222L)),
                () -> inventoryService.holdSeat(new HoldSeatRequest(flightId, "12C", 333L))
        ));

        // Every failure, if any, is an optimistic-lock loss - nothing else.
        assertThat(failures).allSatisfy(failure ->
                assertThat(failure).isInstanceOf(OptimisticLockingFailureException.class));

        int successes = 3 - failures.size();
        assertThat(successes).isGreaterThanOrEqualTo(1);

        long activeHolds = seatHoldRepository.findAll().stream()
                .filter(h -> h.getStatus() == SeatHoldStatus.ACTIVE).count();
        assertThat(activeHolds).isEqualTo(successes);

        FlightInventory inventory = flightInventoryRepository.findById(inventoryId).orElseThrow();
        assertThat(inventory.getHeldSeats()).isEqualTo(successes);
        assertThat(inventory.getAvailableSeats()).isEqualTo(3 - successes);
        assertThat(inventory.getAvailableSeats() + inventory.getHeldSeats()
                + inventory.getReservedSeats() + inventory.getBlockedSeats())
                .isEqualTo(inventory.getTotalSeats());
    }
}
