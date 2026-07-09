package com.skybook.praveen.checkinservice.integration;

import com.skybook.praveen.checkinservice.dto.request.CreateCheckInRequest;
import com.skybook.praveen.checkinservice.enums.CheckInStatus;
import com.skybook.praveen.checkinservice.repository.CheckInRepository;
import com.skybook.praveen.checkinservice.service.CheckInService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two races design doc section 15 flags as candidates: a duplicate
 * concurrent check-in create must not produce two rows for the same
 * bookingPassengerId, and a duplicate concurrent check-in transition must
 * not check the same passenger in twice. Real transactions, real threads,
 * real Postgres - same shape as payment-service's PaymentConcurrencyTest.
 */
class CheckInConcurrencyTest extends AbstractCheckInIntegrationTest {

    private static final AtomicLong PASSENGER_SEQ = new AtomicLong(999_000);
    private static final LocalDateTime DEPARTURE = LocalDateTime.now().plusHours(2);

    @Autowired
    private CheckInService checkInService;
    @Autowired
    private CheckInRepository checkInRepository;

    @BeforeEach
    void cleanUp() {
        checkInRepository.deleteAll();
    }

    private CreateCheckInRequest requestFor(long bookingPassengerId) {
        return new CreateCheckInRequest(42L, "SBCONC", bookingPassengerId, 7L, "BA178", "LHR", "JFK",
                DEPARTURE, "Concurrency Test", "12B", "ECONOMY", "FLEXI", true);
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
            } catch (ExecutionException e) {
                failures.add(e.getCause());
            }
        }
        pool.shutdown();
        return failures;
    }

    @Test
    void duplicateConcurrentCreateResultsInExactlyOneRow() throws Exception {

        long bookingPassengerId = PASSENGER_SEQ.incrementAndGet();
        CreateCheckInRequest request = requestFor(bookingPassengerId);

        List<Throwable> failures = race(List.of(
                () -> checkInService.createCheckIn(request, "KAFKA", "BOOKING_EVENT", "SBCONC"),
                () -> checkInService.createCheckIn(request, "KAFKA", "BOOKING_EVENT", "SBCONC")
        ));

        // Either both "succeed" (the loser's early-return found the winner's
        // already-committed row, no race at all that run) or the loser hits
        // the DB unique constraint - either way, exactly one row survives.
        assertThat(failures).hasSizeLessThanOrEqualTo(1);
        if (!failures.isEmpty()) {
            assertThat(failures.getFirst()).isInstanceOf(DataIntegrityViolationException.class);
        }

        assertThat(checkInRepository.findByBookingPassengerId(bookingPassengerId)).isPresent();
        long count = checkInRepository.findByBookingId(42L).stream()
                .filter(c -> c.getBookingPassengerId().equals(bookingPassengerId))
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void duplicateConcurrentCheckInResultsInExactlyOneChecked() throws Exception {

        long bookingPassengerId = PASSENGER_SEQ.incrementAndGet();
        var created = checkInService.createCheckIn(
                requestFor(bookingPassengerId), "USER", "API", "SBCONC");
        checkInService.openWindow(created.id());
        Long id = created.id();

        List<Throwable> failures = race(List.of(
                () -> checkInService.recordCheckIn(id),
                () -> checkInService.recordCheckIn(id)
        ));

        // Exactly one winner; the loser lost for a legitimate reason.
        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst()).isInstanceOfAny(
                IllegalStateException.class,              // OPEN -> CHECKED_IN -> CHECKED_IN transition rejected
                OptimisticLockingFailureException.class,  // @Version collision at commit
                DataIntegrityViolationException.class
        );

        var after = checkInService.getById(id);
        assertThat(after.status()).isEqualTo(CheckInStatus.CHECKED_IN);
        assertThat(after.checkedInAt()).isNotNull();
    }
}
