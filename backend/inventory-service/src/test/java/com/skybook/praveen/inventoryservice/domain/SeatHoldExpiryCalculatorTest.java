package com.skybook.praveen.inventoryservice.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SeatHoldExpiryCalculatorTest {

    private final SeatHoldExpiryCalculator calculator = new SeatHoldExpiryCalculator(15);

    @Test
    void expiryIsHeldAtPlusConfiguredTtl() {
        LocalDateTime heldAt = LocalDateTime.of(2026, 7, 3, 10, 0);

        assertThat(calculator.calculateExpiry(heldAt))
                .isEqualTo(LocalDateTime.of(2026, 7, 3, 10, 15));
    }

    @Test
    void ttlIsConfigurable() {
        SeatHoldExpiryCalculator fiveMinutes = new SeatHoldExpiryCalculator(5);
        LocalDateTime heldAt = LocalDateTime.of(2026, 7, 3, 10, 0);

        assertThat(fiveMinutes.calculateExpiry(heldAt))
                .isEqualTo(LocalDateTime.of(2026, 7, 3, 10, 5));
        assertThat(fiveMinutes.getTtlMinutes()).isEqualTo(5);
    }

    @Test
    void notExpiredBeforeTheBoundary() {
        LocalDateTime expiresAt = LocalDateTime.of(2026, 7, 3, 10, 15);

        assertThat(calculator.isExpired(expiresAt, expiresAt.minusSeconds(1))).isFalse();
    }

    @Test
    void notExpiredExactlyAtTheBoundary() {
        // isAfter, not isEqual - a hold expiring at 10:15:00 is still valid at 10:15:00.
        LocalDateTime expiresAt = LocalDateTime.of(2026, 7, 3, 10, 15);

        assertThat(calculator.isExpired(expiresAt, expiresAt)).isFalse();
    }

    @Test
    void expiredAfterTheBoundary() {
        LocalDateTime expiresAt = LocalDateTime.of(2026, 7, 3, 10, 15);

        assertThat(calculator.isExpired(expiresAt, expiresAt.plusSeconds(1))).isTrue();
    }
}
