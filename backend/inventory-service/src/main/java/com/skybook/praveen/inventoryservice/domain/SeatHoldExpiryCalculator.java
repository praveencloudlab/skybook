package com.skybook.praveen.inventoryservice.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Owns the hold-TTL policy. Kept as its own class (rather than a constant in
 * the service) so the TTL is configurable per environment and the expiry
 * decision is unit-testable in isolation.
 */
@Component
public class SeatHoldExpiryCalculator {

    private final long ttlMinutes;

    public SeatHoldExpiryCalculator(@Value("${inventory.hold.ttl-minutes:15}") long ttlMinutes) {
        this.ttlMinutes = ttlMinutes;
    }

    public LocalDateTime calculateExpiry(LocalDateTime heldAt) {
        return heldAt.plusMinutes(ttlMinutes);
    }

    public boolean isExpired(LocalDateTime expiresAt, LocalDateTime now) {
        return now.isAfter(expiresAt);
    }

    public long getTtlMinutes() {
        return ttlMinutes;
    }
}
