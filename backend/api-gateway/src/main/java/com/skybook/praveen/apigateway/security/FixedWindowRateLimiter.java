package com.skybook.praveen.apigateway.security;

import com.skybook.praveen.apigateway.config.RateLimitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory, per-client-key (client IP in practice) fixed-window rate
 * limiter (design doc §6). Deliberately not Spring Cloud Gateway's built-in
 * RequestRateLimiter - that filter is Redis-backed in both the reactive and
 * WebMVC flavors, and this stack has no Redis instance yet. A single
 * ConcurrentHashMap is sufficient because this gateway runs as one
 * instance; horizontally scaling it would need this to move to Redis so
 * the limit is shared across instances (documented limitation, §11).
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class FixedWindowRateLimiter {

    private record Window(long windowStartMinute, int count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int limit;

    public FixedWindowRateLimiter(RateLimitProperties properties) {
        this.limit = properties.getRequestsPerMinute();
    }

    /** True if this call is within the limit for the current one-minute window; false if it should be rejected. */
    public boolean tryAcquire(String key) {
        long currentMinute = System.currentTimeMillis() / 60_000;
        AtomicBoolean allowed = new AtomicBoolean();

        windows.compute(key, (k, existing) -> {
            if (existing == null || existing.windowStartMinute() != currentMinute) {
                allowed.set(true);
                return new Window(currentMinute, 1);
            }
            if (existing.count() < limit) {
                allowed.set(true);
                return new Window(currentMinute, existing.count() + 1);
            }
            allowed.set(false);
            return existing;
        });

        return allowed.get();
    }

    /** Drops windows from prior minutes so the map doesn't grow unbounded with one-off clients. */
    @Scheduled(fixedRate = 5, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    void evictStaleWindows() {
        long currentMinute = System.currentTimeMillis() / 60_000;
        windows.entrySet().removeIf(entry -> entry.getValue().windowStartMinute() != currentMinute);
    }
}
