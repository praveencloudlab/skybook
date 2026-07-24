package com.skybook.praveen.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceTokenProviderTest {

    @Test
    void cachesAFreshTokenAndDoesNotRefetch() {
        AtomicInteger fetches = new AtomicInteger();
        ServiceTokenProvider provider = new ServiceTokenProvider(aud -> {
            fetches.incrementAndGet();
            return new ServiceTokenProvider.ServiceToken(
                    "token-for-" + aud, Instant.now().plus(10, ChronoUnit.MINUTES));
        });

        assertThat(provider.tokenFor("inventory-service")).isEqualTo("token-for-inventory-service");
        assertThat(provider.tokenFor("inventory-service")).isEqualTo("token-for-inventory-service");
        assertThat(fetches).hasValue(1);
    }

    @Test
    void keepsSeparateTokensPerAudience() {
        ServiceTokenProvider provider = new ServiceTokenProvider(aud ->
                new ServiceTokenProvider.ServiceToken("t-" + aud, Instant.now().plus(10, ChronoUnit.MINUTES)));

        assertThat(provider.tokenFor("inventory-service")).isEqualTo("t-inventory-service");
        assertThat(provider.tokenFor("flight-service")).isEqualTo("t-flight-service");
    }

    @Test
    void refetchesWhenTheCachedTokenIsWithinTheRefreshSkew() {
        AtomicInteger fetches = new AtomicInteger();
        ServiceTokenProvider provider = new ServiceTokenProvider(aud -> {
            int n = fetches.incrementAndGet();
            // First token expires almost immediately (inside the 30s skew), so
            // the next call must refetch; second is long-lived.
            Instant exp = n == 1
                    ? Instant.now().plus(5, ChronoUnit.SECONDS)
                    : Instant.now().plus(10, ChronoUnit.MINUTES);
            return new ServiceTokenProvider.ServiceToken("token-" + n, exp);
        });

        assertThat(provider.tokenFor("inventory-service")).isEqualTo("token-1");
        assertThat(provider.tokenFor("inventory-service")).isEqualTo("token-2");
        assertThat(fetches).hasValue(2);
    }

    @Test
    void invalidateForcesARefetch() {
        AtomicInteger fetches = new AtomicInteger();
        ServiceTokenProvider provider = new ServiceTokenProvider(aud -> {
            fetches.incrementAndGet();
            return new ServiceTokenProvider.ServiceToken("t", Instant.now().plus(10, ChronoUnit.MINUTES));
        });

        provider.tokenFor("inventory-service");
        provider.invalidate("inventory-service");
        provider.tokenFor("inventory-service");
        assertThat(fetches).hasValue(2);
    }
}
