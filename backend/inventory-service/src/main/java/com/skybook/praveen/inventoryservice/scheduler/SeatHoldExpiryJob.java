package com.skybook.praveen.inventoryservice.scheduler;

import com.skybook.praveen.inventoryservice.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps ACTIVE seat holds past their TTL and expires them, returning the
 * seats to the available pool. The heavy lifting (state transition, counts,
 * history) lives in InventoryService.expireHolds() so it stays unit-testable
 * without scheduling machinery.
 *
 * @EnableScheduling lives here rather than on the application class so the
 * scheduling infrastructure is owned by the one component that uses it.
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class SeatHoldExpiryJob {

    private final InventoryService inventoryService;

    @Scheduled(fixedDelayString = "${inventory.hold.sweep-interval-ms:60000}")
    public void expireHolds() {

        int expired = inventoryService.expireHolds();

        if (expired > 0) {
            log.info("SeatHoldExpiryJob expired {} hold(s)", expired);
        }
    }
}
