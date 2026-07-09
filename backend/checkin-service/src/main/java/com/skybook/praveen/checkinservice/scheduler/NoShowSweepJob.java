package com.skybook.praveen.checkinservice.scheduler;

import com.skybook.praveen.checkinservice.facade.CheckInFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps every pre-boarding CheckIn whose gate has closed and marks it
 * NO_SHOW (design doc section 5.7/10). The heavy lifting - cutoff
 * computation, the transition, revoking any boarding pass, publishing the
 * event - lives in CheckInFacade.sweepNoShows() so it stays unit-testable
 * without scheduling machinery (same discipline as inventory-service's
 * SeatHoldExpiryJob).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoShowSweepJob {

    private final CheckInFacade checkInFacade;

    @Scheduled(fixedDelayString = "${checkin.sweep.no-show-interval-ms:60000}")
    public void sweepNoShows() {

        int swept = checkInFacade.sweepNoShows();

        if (swept > 0) {
            log.info("NoShowSweepJob marked {} check-in(s) as NO_SHOW", swept);
        }
    }
}
