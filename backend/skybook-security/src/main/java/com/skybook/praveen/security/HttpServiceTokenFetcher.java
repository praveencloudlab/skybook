package com.skybook.praveen.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Fetches a {@code ROLE_SERVICE} token from auth-service's internal
 * {@code POST /api/auth/service-token} using this service's client credential
 * (HTTP Basic) - SECURITY_HARDENING_MODULE.md §3.3. The token's absolute expiry
 * is read from its own {@code exp} claim, so {@link ServiceTokenProvider} can
 * refresh just before it lapses without any extra response field.
 */
public class HttpServiceTokenFetcher implements ServiceTokenProvider.ServiceTokenFetcher {

    private final RestClient restClient;
    private final ServiceClientProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HttpServiceTokenFetcher(ServiceClientProperties properties) {
        this.properties = properties;
        // Bounded timeouts (fail closed): a hung auth-service must not stall the
        // caller's request thread indefinitely while it waits for a token.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder()
                .baseUrl(properties.getAuthBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public ServiceTokenProvider.ServiceToken fetch(String audience) {
        String token = restClient.post()
                .uri("/api/auth/service-token")
                .headers(h -> h.setBasicAuth(properties.getClientId(), properties.getClientSecret()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("audience", audience))
                .retrieve()
                .body(String.class);

        if (token == null || token.isBlank()) {
            throw new IllegalStateException("auth-service returned an empty service token for audience " + audience);
        }
        return new ServiceTokenProvider.ServiceToken(token, expiryOf(token));
    }

    /**
     * Reads the {@code exp} (epoch seconds) from the JWT payload. Fail closed: a
     * token we cannot parse, or one carrying no {@code exp}, is rejected outright
     * rather than being trusted for a fixed grace window - the downstream
     * validator requires {@code exp}, so caching an unparseable token would just
     * yield a token that is rejected on every use.
     */
    private Instant expiryOf(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            throw new IllegalStateException("auth-service returned a malformed service token");
        }
        JsonNode expNode;
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            expNode = objectMapper.readTree(payload).get("exp");
        } catch (Exception e) {
            throw new IllegalStateException("auth-service returned a malformed service token", e);
        }
        if (expNode == null || !expNode.canConvertToLong()) {
            throw new IllegalStateException("service token has no usable exp claim");
        }
        return Instant.ofEpochSecond(expNode.asLong());
    }
}
