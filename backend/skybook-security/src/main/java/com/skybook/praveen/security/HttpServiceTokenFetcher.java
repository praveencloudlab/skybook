package com.skybook.praveen.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

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
        this.restClient = RestClient.builder()
                .baseUrl(properties.getAuthBaseUrl())
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

    /** Reads the {@code exp} (epoch seconds) from the JWT payload. */
    private Instant expiryOf(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode node = objectMapper.readTree(payload);
            return Instant.ofEpochSecond(node.get("exp").asLong());
        } catch (Exception e) {
            // Fall back to a conservative short lifetime if the token can't be parsed.
            return Instant.now().plusSeconds(60);
        }
    }
}
