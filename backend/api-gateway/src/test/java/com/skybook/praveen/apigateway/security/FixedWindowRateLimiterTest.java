package com.skybook.praveen.apigateway.security;

import com.skybook.praveen.apigateway.config.RateLimitProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowRateLimiterTest {

    private static RateLimitProperties limitOf(int requestsPerMinute) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setRequestsPerMinute(requestsPerMinute);
        return properties;
    }

    @Test
    void allowsRequestsUpToTheLimitThenRejects() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(limitOf(3));

        assertThat(limiter.tryAcquire("1.2.3.4")).isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4")).isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4")).isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4")).isFalse();
    }

    @Test
    void tracksEachClientKeyIndependently() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(limitOf(1));

        assertThat(limiter.tryAcquire("1.2.3.4")).isTrue();
        assertThat(limiter.tryAcquire("5.6.7.8")).isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4")).isFalse();
        assertThat(limiter.tryAcquire("5.6.7.8")).isFalse();
    }

    @Test
    void evictStaleWindowsDropsEntriesFromPriorMinutesButKeepsTheCurrentOne() throws Exception {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(limitOf(1));

        Field windowsField = FixedWindowRateLimiter.class.getDeclaredField("windows");
        windowsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> windows = (Map<String, Object>) windowsField.get(limiter);

        Class<?> windowClass = Class.forName(FixedWindowRateLimiter.class.getName() + "$Window");
        Constructor<?> windowConstructor = windowClass.getDeclaredConstructor(long.class, int.class);
        windowConstructor.setAccessible(true);
        windows.put("stale-client", windowConstructor.newInstance(0L, 1));

        limiter.tryAcquire("fresh-client");
        limiter.evictStaleWindows();

        assertThat(windows).doesNotContainKey("stale-client");
        assertThat(windows).containsKey("fresh-client");
    }
}
