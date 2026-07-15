package com.skybook.praveen.inventoryservice.integration;

import com.skybook.praveen.inventoryservice.dto.request.AutoHoldSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.HoldSeatRequest;
import com.skybook.praveen.inventoryservice.entity.Aircraft;
import com.skybook.praveen.inventoryservice.entity.AircraftSeat;
import com.skybook.praveen.inventoryservice.entity.FlightInventory;
import com.skybook.praveen.inventoryservice.entity.SeatHold;
import com.skybook.praveen.inventoryservice.enums.SeatHoldStatus;
import com.skybook.praveen.inventoryservice.enums.SeatPosition;
import com.skybook.praveen.inventoryservice.enums.SeatType;
import com.skybook.praveen.inventoryservice.exception.SeatAlreadyHeldException;
import com.skybook.praveen.inventoryservice.exception.SeatNotAvailableException;
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
 * Proves the concurrency claim from SEAT_SELECTION_MODULE.md §5: every
 * counter-mutating hold - manual AND auto - serializes on the pessimistic
 * flight lock (SELECT ... FOR UPDATE on the FlightInventory row). Racing holds
 * therefore never oversell and never pick the same seat; the loser (if any)
 * fails cleanly, and the count invariant always holds.
 *
 * Real PostgreSQL, real transactions, real threads - each call runs in its own
 * transaction (this test class is deliberately NOT @Transactional).
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

    private HoldSeatRequest manual(String seatNumber, long booking) {
        return new HoldSeatRequest(flightId, seatNumber, booking, booking * 10, SeatType.ECONOMY);
    }

    private AutoHoldSeatRequest auto(long booking) {
        return new AutoHoldSeatRequest(booking, booking * 10, SeatType.ECONOMY);
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

    private void assertInvariant(FlightInventory inventory) {
        assertThat(inventory.getAvailableSeats() + inventory.getHeldSeats()
                + inventory.getReservedSeats() + inventory.getBlockedSeats())
                .isEqualTo(inventory.getTotalSeats());
    }

    private long activeHolds() {
        return seatHoldRepository.findAll().stream()
                .filter(h -> h.getStatus() == SeatHoldStatus.ACTIVE).count();
    }

    @Test
    void twoBookingsRacingForTheSameSeatYieldExactlyOneHold() throws Exception {

        List<Throwable> failures = race(List.of(
                () -> inventoryService.holdSeat(manual("12A", 111L)),
                () -> inventoryService.holdSeat(manual("12A", 222L))
        ));

        // The flight lock serializes them: exactly one winner, the loser sees
        // the winner's hold and fails cleanly (no rollback-only / lock-timeout).
        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst()).isInstanceOf(SeatAlreadyHeldException.class);

        assertThat(activeHolds()).isEqualTo(1);

        FlightInventory inventory = flightInventoryRepository.findById(inventoryId).orElseThrow();
        assertThat(inventory.getAvailableSeats()).isEqualTo(2);
        assertThat(inventory.getHeldSeats()).isEqualTo(1);
        assertInvariant(inventory);
    }

    @Test
    void manualRacesOnDifferentSeatsAllSucceedUnderTheFlightLock() throws Exception {

        // Three bookings, three different seats. Under the pessimistic flight
        // lock they serialize with NO optimistic-lock loss - all three commit.
        List<Throwable> failures = race(List.of(
                () -> inventoryService.holdSeat(manual("12A", 111L)),
                () -> inventoryService.holdSeat(manual("12B", 222L)),
                () -> inventoryService.holdSeat(manual("12C", 333L))
        ));

        assertThat(failures).isEmpty();
        assertThat(activeHolds()).isEqualTo(3);

        FlightInventory inventory = flightInventoryRepository.findById(inventoryId).orElseThrow();
        assertThat(inventory.getHeldSeats()).isEqualTo(3);
        assertThat(inventory.getAvailableSeats()).isZero();
        assertInvariant(inventory);
    }

    @Test
    void twoAutoAssignsGetDifferentSeatsNeverTheSameOne() throws Exception {

        // The design's headline guarantee: two atomic auto-assigns racing the
        // same flight get DIFFERENT seats, no rollback-only error, counts right.
        List<Throwable> failures = race(List.of(
                () -> inventoryService.autoHoldSeat(flightId, auto(111L)),
                () -> inventoryService.autoHoldSeat(flightId, auto(222L))
        ));

        assertThat(failures).isEmpty();

        List<SeatHold> holds = seatHoldRepository.findAll().stream()
                .filter(h -> h.getStatus() == SeatHoldStatus.ACTIVE).toList();
        assertThat(holds).hasSize(2);
        assertThat(holds.stream().map(h -> h.getAircraftSeat().getId()).distinct()).hasSize(2);
        // AUTO seats are free regardless of what they list at.
        assertThat(holds).allSatisfy(h -> assertThat(h.getChargedSurcharge()).isEqualByComparingTo("0.00"));

        FlightInventory inventory = flightInventoryRepository.findById(inventoryId).orElseThrow();
        assertThat(inventory.getHeldSeats()).isEqualTo(2);
        assertThat(inventory.getAvailableSeats()).isEqualTo(1);
        assertInvariant(inventory);
    }

    @Test
    void autoVsManualRacingSerializeWithoutOversell() throws Exception {

        // An auto-assign and a manual pick on the same flight serialize on the
        // shared lock: no oversell, no rollback-only error. If they collide on
        // the same seat exactly one wins; otherwise both hold different seats.
        List<Throwable> failures = race(List.of(
                () -> inventoryService.autoHoldSeat(flightId, auto(111L)),
                () -> inventoryService.holdSeat(manual("12A", 222L))
        ));

        assertThat(failures).allSatisfy(failure -> assertThat(failure)
                .isInstanceOfAny(SeatAlreadyHeldException.class, SeatNotAvailableException.class));

        long successes = 2 - failures.size();
        assertThat(successes).isGreaterThanOrEqualTo(1);
        assertThat(activeHolds()).isEqualTo(successes);

        FlightInventory inventory = flightInventoryRepository.findById(inventoryId).orElseThrow();
        assertThat(inventory.getHeldSeats()).isEqualTo(successes);
        assertInvariant(inventory);
    }

    @Test
    void autoRetryForTheSamePassengerIsIdempotent() throws Exception {

        // Two identical auto-assign calls for the SAME passenger race: money
        // idempotency returns the one stored hold - never two seats, never two
        // charges.
        List<Throwable> failures = race(List.of(
                () -> inventoryService.autoHoldSeat(flightId, auto(111L)),
                () -> inventoryService.autoHoldSeat(flightId, auto(111L))
        ));

        assertThat(failures).isEmpty();
        assertThat(activeHolds()).isEqualTo(1);

        FlightInventory inventory = flightInventoryRepository.findById(inventoryId).orElseThrow();
        assertThat(inventory.getHeldSeats()).isEqualTo(1);
        assertThat(inventory.getAvailableSeats()).isEqualTo(2);
        assertInvariant(inventory);
    }
}
