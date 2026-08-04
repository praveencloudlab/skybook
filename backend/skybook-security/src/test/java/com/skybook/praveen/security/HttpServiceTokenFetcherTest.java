package com.skybook.praveen.security;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client half of the service-token exchange (SECURITY_HARDENING_MODULE.md
 * §3.3), driven against a loopback stub standing in for auth-service. The
 * fetcher builds its own {@code RestClient} internally, so the only way to
 * observe what it actually puts on the wire - the Basic credential and the
 * requested audience - is to serve the request and look.
 *
 * <p>Nothing here verifies a signature: minting is auth-service's job and
 * verification is the receiving service's ({@link JwtTokenValidator}). What this
 * side must get right is failing closed on anything it cannot schedule a refresh
 * from.
 */
class HttpServiceTokenFetcherTest {

    private static final String CLIENT_ID = "booking-service";
    private static final String CLIENT_SECRET = "s3cr3t-value";
    private static final String AUDIENCE = "inventory-service";

    private HttpServer server;

    // Written on the stub's handler thread, read on the test thread.
    private volatile String capturedAuthorization;
    private volatile String capturedContentType;
    private volatile String capturedBody;
    private volatile String responseBody;
    private volatile int responseStatus = 200;

    @BeforeEach
    void startStubAuthService() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/service-token", exchange -> {
            capturedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            capturedContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            capturedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            byte[] payload = responseBody == null
                    ? new byte[0]
                    : responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain;charset=UTF-8");
            // -1 means "no body at all", which is what an empty token response is.
            exchange.sendResponseHeaders(responseStatus, payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) {
                exchange.getResponseBody().write(payload);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStubAuthService() {
        server.stop(0);
    }

    private HttpServiceTokenFetcher fetcher() {
        ServiceClientProperties properties = new ServiceClientProperties();
        properties.setAuthBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setClientId(CLIENT_ID);
        properties.setClientSecret(CLIENT_SECRET);
        return new HttpServiceTokenFetcher(properties);
    }

    /** A structurally valid JWT carrying the given payload; never signed for real. */
    private static String jwtWithPayload(String payloadJson) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString("signature".getBytes(StandardCharsets.UTF_8));
    }

    @Nested
    @DisplayName("the outgoing request identifies this service")
    class OutgoingRequest {

        @Test
        void presentsItsOwnClientCredentialAsHttpBasic() {
            long exp = Instant.now().plus(10, ChronoUnit.MINUTES).getEpochSecond();
            responseBody = jwtWithPayload("{\"exp\":" + exp + "}");

            fetcher().fetch(AUDIENCE);

            String expected = "Basic " + Base64.getEncoder().encodeToString(
                    (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
            assertThat(capturedAuthorization).isEqualTo(expected);
        }

        @Test
        void asksForExactlyTheAudienceItWasGiven() {
            long exp = Instant.now().plus(10, ChronoUnit.MINUTES).getEpochSecond();
            responseBody = jwtWithPayload("{\"exp\":" + exp + "}");

            fetcher().fetch("payment-service");

            assertThat(capturedContentType).contains("application/json");
            assertThat(capturedBody).isEqualTo("{\"audience\":\"payment-service\"}");
        }
    }

    @Nested
    @DisplayName("a usable token is returned with the expiry its own claim states")
    class SuccessfulFetch {

        @Test
        void readsTheAbsoluteExpiryFromTheExpClaimRatherThanGuessingATtl() {
            // Anchored to now, so the assertion cannot rot into a fixed date.
            long exp = Instant.now().plus(10, ChronoUnit.MINUTES).getEpochSecond();
            String token = jwtWithPayload("{\"sub\":\"" + CLIENT_ID + "\",\"exp\":" + exp + "}");
            responseBody = token;

            ServiceTokenProvider.ServiceToken fetched = fetcher().fetch(AUDIENCE);

            assertThat(fetched.token()).isEqualTo(token);
            assertThat(fetched.expiresAt()).isEqualTo(Instant.ofEpochSecond(exp));
        }

        @Test
        void toleratesExtraClaimsAroundTheExp() {
            long exp = Instant.now().plus(3, ChronoUnit.MINUTES).getEpochSecond();
            responseBody = jwtWithPayload(
                    "{\"iss\":\"skybook-auth\",\"token_type\":\"service\","
                            + "\"roles\":[\"ROLE_SERVICE\"],\"exp\":" + exp + ",\"iat\":1}");

            assertThat(fetcher().fetch(AUDIENCE).expiresAt()).isEqualTo(Instant.ofEpochSecond(exp));
        }
    }

    @Nested
    @DisplayName("anything unusable fails closed instead of being cached")
    class FailsClosed {

        @Test
        void rejectsAnEmptyTokenResponse() {
            responseBody = null;

            assertThatThrownBy(() -> fetcher().fetch(AUDIENCE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("empty service token")
                    .hasMessageContaining(AUDIENCE);
        }

        @Test
        void rejectsABlankTokenResponse() {
            responseBody = "   ";

            assertThatThrownBy(() -> fetcher().fetch(AUDIENCE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("empty service token");
        }

        @Test
        void rejectsSomethingThatIsNotAJwtAtAll() {
            responseBody = "just-an-opaque-string";

            assertThatThrownBy(() -> fetcher().fetch(AUDIENCE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("malformed service token");
        }

        @Test
        void rejectsAPayloadSegmentThatIsNotBase64() {
            responseBody = "header.!!!not-base64!!!.signature";

            assertThatThrownBy(() -> fetcher().fetch(AUDIENCE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("malformed service token");
        }

        @Test
        void rejectsAPayloadThatIsNotJson() {
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("this is not json".getBytes(StandardCharsets.UTF_8));
            responseBody = "header." + encoded + ".signature";

            assertThatThrownBy(() -> fetcher().fetch(AUDIENCE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("malformed service token");
        }

        @Test
        void rejectsATokenCarryingNoExpClaim() {
            // Caching a token with no expiry would mean caching one the receiving
            // validator rejects on every single use.
            responseBody = jwtWithPayload("{\"sub\":\"" + CLIENT_ID + "\"}");

            assertThatThrownBy(() -> fetcher().fetch(AUDIENCE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no usable exp claim");
        }

        @Test
        void rejectsATokenWhoseExpIsNotANumber() {
            responseBody = jwtWithPayload("{\"exp\":\"tomorrow\"}");

            assertThatThrownBy(() -> fetcher().fetch(AUDIENCE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no usable exp claim");
        }

        @Test
        void surfacesAnAuthServiceRejectionRatherThanContinuingWithoutTheToken() {
            // A revoked or mistyped client credential must break the outbound call
            // loudly; silently proceeding would send an unauthenticated request.
            responseStatus = 401;
            responseBody = null;

            assertThatThrownBy(() -> fetcher().fetch(AUDIENCE))
                    .isInstanceOf(HttpClientErrorException.Unauthorized.class);
        }
    }
}
