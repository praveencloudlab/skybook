package com.skybook.praveen.apigateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end round trip through the real gateway (design doc §13's "End-to-end"
 * row): a stub JDK HttpServer stands in for every downstream service (no
 * WireMock dependency exists in this repo yet, and the JDK's own
 * com.sun.net.httpserver is enough to assert on the path/headers a route
 * receives), so this exercises routing, JWT enforcement, CORS, and the
 * downstream-error-handling filter together, the way a real client would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingIntegrationTest {

    private static final String ISSUER = "skybook-auth-test";
    private static final String AUDIENCE = "skybook-api-test";

    // RS256 keypair generated once: the gateway verifies with the public key,
    // the test signs valid tokens with the matching private key.
    private static final KeyPair KEY_PAIR = generateRsa();

    private static HttpServer stubDownstream;
    private static int closedPort;

    @Autowired
    private TestRestTemplate restTemplate;

    private static KeyPair generateRsa() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeAll
    static void startStubDownstream() throws IOException {
        stubDownstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stubDownstream.createContext("/api/auth/login", exchange -> respond(exchange, 200, "auth-ok"));
        stubDownstream.createContext("/api/flights/1", GatewayRoutingIntegrationTest::respondWithAuthUser);
        stubDownstream.start();

        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
    }

    @AfterAll
    static void stopStubDownstream() {
        stubDownstream.stop(0);
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("skybook.security.public-key",
                () -> Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded()));
        registry.add("skybook.security.issuer", () -> ISSUER);
        registry.add("skybook.security.user-audience", () -> AUDIENCE);
        registry.add("skybook.security.accept-service-tokens", () -> "false");
        registry.add("services.auth-service.base-url", GatewayRoutingIntegrationTest::stubBaseUrl);
        registry.add("services.flight-service.base-url", GatewayRoutingIntegrationTest::stubBaseUrl);
        registry.add("services.inventory-service.base-url", GatewayRoutingIntegrationTest::stubBaseUrl);
        registry.add("services.payment-service.base-url", GatewayRoutingIntegrationTest::stubBaseUrl);
        registry.add("services.checkin-service.base-url", GatewayRoutingIntegrationTest::stubBaseUrl);
        registry.add("services.booking-service.base-url", () -> "http://localhost:" + closedPort);
    }

    private static String stubBaseUrl() {
        return "http://localhost:" + stubDownstream.getAddress().getPort();
    }

    private static void respondWithAuthUser(HttpExchange exchange) throws IOException {
        String authUser = exchange.getRequestHeaders().getFirst("X-Auth-User");
        respond(exchange, 200, "flight-ok:" + authUser);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String validTokenFor(String subject) {
        return Jwts.builder()
                .subject(subject)
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .claim("token_type", "user")
                .claim("roles", List.of("ROLE_USER"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith((RSAPrivateKey) KEY_PAIR.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    @Test
    void publicRouteReachesTheDownstreamServiceWithoutAToken() {
        ResponseEntity<String> response =
                restTemplate.postForEntity("/api/auth/login", HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("auth-ok");
    }

    @Test
    void protectedRouteWithoutATokenReturns401() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/flights/1", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedRouteWithAValidTokenReachesTheDownstreamServiceWithAuthUserHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(validTokenFor("traveler@skybook.com"));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/flights/1", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("flight-ok:traveler@skybook.com");
    }

    @Test
    void unreachableDownstreamServiceReturns502() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(validTokenFor("traveler@skybook.com"));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/bookings/1", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).contains("Downstream service unreachable");
    }

    @Test
    void corsPreflightForAnAllowedOriginGetsTheAllowOriginHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, "http://localhost:5173");
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/flights/1", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://localhost:5173");
    }

    @Test
    void corsPreflightForADisallowedOriginGetsNoAllowOriginHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, "http://evil.example.com");
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/flights/1", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }
}
