package com.skybook.praveen.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Supplies short-lived {@code ROLE_SERVICE} tokens for outbound service→service
 * calls (SECURITY_HARDENING_MODULE.md §3.3), caching per target audience and
 * refreshing shortly BEFORE expiry - never per call, never stale.
 *
 * The actual HTTP fetch against auth-service's {@code POST /api/auth/service-token}
 * is a {@link ServiceTokenFetcher} injected by the consuming service (wired in
 * §13 step 3). Keeping the fetch behind an interface lets this caching logic be
 * built and unit-tested now, before that endpoint exists, and keeps this shared
 * module free of an HTTP-client dependency.
 */
public class ServiceTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenProvider.class);

    /** Refresh this long before the token actually expires, to avoid racing expiry mid-call. */
    private static final Duration REFRESH_SKEW = Duration.ofSeconds(30);

    private final ServiceTokenFetcher fetcher;
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    public ServiceTokenProvider(ServiceTokenFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** A valid {@code ROLE_SERVICE} token for {@code audience}, fetching or refreshing as needed. */
    public String tokenFor(String audience) {
        CachedToken current = cache.get(audience);
        if (current != null && current.isFresh()) {
            return current.token();
        }
        // compute() serializes concurrent refreshers for the same audience.
        return cache.compute(audience, (aud, existing) -> {
            if (existing != null && existing.isFresh()) {
                return existing;
            }
            log.debug("Fetching a fresh ROLE_SERVICE token for audience {}", aud);
            ServiceToken fetched = fetcher.fetch(aud);
            return new CachedToken(fetched.token(), fetched.expiresAt());
        }).token();
    }

    /** Drops any cached token for the audience (e.g. after a downstream 401). */
    public void invalidate(String audience) {
        cache.remove(audience);
    }

    private record CachedToken(String token, Instant expiresAt) {
        boolean isFresh() {
            return Instant.now().isBefore(expiresAt.minus(REFRESH_SKEW));
        }
    }

    /** A minted service token and its absolute expiry. */
    public record ServiceToken(String token, Instant expiresAt) {
    }

    /** Fetches a fresh {@code ROLE_SERVICE} token for a target audience from auth-service. */
    @FunctionalInterface
    public interface ServiceTokenFetcher {
        ServiceToken fetch(String audience);
    }
}
