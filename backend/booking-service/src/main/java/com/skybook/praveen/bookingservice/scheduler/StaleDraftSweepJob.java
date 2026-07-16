package com.skybook.praveen.bookingservice.scheduler;

import com.skybook.praveen.bookingservice.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cancels DRAFT bookings older than the configured TTL
 * (SEAT_SELECTION_MODULE.md §5.1a) - a JVM crash between draft-commit and
 * finalize would otherwise leave permanent orphans. The matching inventory
 * holds expire on their own (SeatHoldExpiryJob); this is the booking-side
 * half of that cleanup. The heavy lifting lives in
 * BookingService.cancelStaleDrafts() so it stays unit-testable without
 * scheduling machinery - same pattern as inventory's SeatHoldExpiryJob.
 *
 * @EnableScheduling lives here rather than on the application class so the
 * scheduling infrastructure is owned by the one component that uses it.
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class StaleDraftSweepJob {

    private final BookingService bookingService;

    @Scheduled(fixedDelayString = "${booking.draft.sweep-interval-ms:60000}")
    public void sweepStaleDrafts() {

        int swept = bookingService.cancelStaleDrafts();

        if (swept > 0) {
            log.info("StaleDraftSweepJob cancelled {} stale draft(s)", swept);
        }
    }
}
