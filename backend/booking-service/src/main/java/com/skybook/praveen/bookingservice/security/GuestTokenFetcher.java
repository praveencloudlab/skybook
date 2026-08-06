package com.skybook.praveen.bookingservice.security;

import com.skybook.praveen.security.ServiceClientProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * Fetches a booking-scoped GUEST session token from auth-service
 * (GUEST_CHECKIN_MODULE.md §3.1) - the same HTTP-Basic client credential and
 * the same internal-only chain the service-token fetcher uses, one endpoint
 * over. No caching, deliberately: every guest session is a fresh 30-minute
 * token for a specific booking; there is nothing reusable to cache.
 */
@Component
public class GuestTokenFetcher {

    private final RestClient restClient;
    private final ServiceClientProperties properties;

    public GuestTokenFetcher(ServiceClientProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder()
                .baseUrl(properties.getAuthBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public String fetch(long bookingId) {
        String token = restClient.post()
                .uri("/api/auth/guest-token")
                .headers(h -> h.setBasicAuth(properties.getClientId(), properties.getClientSecret()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("bookingId", bookingId))
                .retrieve()
                .body(String.class);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("auth-service returned an empty guest token");
        }
        return token;
    }
}
