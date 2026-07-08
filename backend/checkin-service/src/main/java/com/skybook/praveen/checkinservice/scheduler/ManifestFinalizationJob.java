package com.skybook.praveen.checkinservice.scheduler;

import com.skybook.praveen.checkinservice.service.ManifestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finalizes every flight manifest whose gate has closed and that isn't
 * already FINALIZED (design doc section 5.7/10). Delegates to
 * ManifestService.finalizeDueManifests() for unit-testability, same
 * discipline as NoShowSweepJob/SeatHoldExpiryJob.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManifestFinalizationJob {

    private final ManifestService manifestService;

    @Scheduled(fixedDelayString = "${checkin.sweep.manifest-finalize-interval-ms:60000}")
    public void finalizeDueManifests() {

        int finalized = manifestService.finalizeDueManifests();

        if (finalized > 0) {
            log.info("ManifestFinalizationJob finalized {} manifest(s)", finalized);
        }
    }
}
