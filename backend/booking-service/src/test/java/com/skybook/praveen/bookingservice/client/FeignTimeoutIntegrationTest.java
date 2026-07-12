package com.skybook.praveen.bookingservice.client;

import com.skybook.praveen.bookingservice.exception.FlightServiceUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic read-timeout verification (RESILIENCE_MODULE.md §4/§15):
 * a local stub accepts the connection and then sleeps far past the 5s
 * configured read timeout - without the timeout config, Feign's default
 * would wait 60 SECONDS for this response. Asserts the call fails fast
 * and surfaces as the wrapper's domain exception, not a raw Feign one.
 *
 * The elapsed-time bound is deliberately generous (20s): once read-only
 * retries land (§6, 3 attempts with backoff), a fully-retried read is
 * ~3×5s + backoff - still an order of magnitude under the 60s default
 * this test exists to guard against.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class FeignTimeoutIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static HttpServer slowFlightService;

    static {
        POSTGRES.start();
    }

    @BeforeAll
    static void startSlowStub() throws IOException {
        slowFlightService = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        slowFlightService.createContext("/", exchange -> {
            try {
                Thread.sleep(60_000); // never respond within any sane window
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        slowFlightService.start();
    }

    @AfterAll
    static void stopSlowStub() {
        slowFlightService.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("flight-service.base-url",
                () -> "http://localhost:" + slowFlightService.getAddress().getPort());
    }

    @Autowired
    private FlightServiceClient flightServiceClient;

    @Test
    void readTimeoutBoundsASlowDownstreamToSecondsNotAMinute() {
        Instant start = Instant.now();

        assertThatThrownBy(() -> flightServiceClient.getFlight(1L))
                .isInstanceOf(FlightServiceUnavailableException.class);

        Duration elapsed = Duration.between(start, Instant.now());
        // Nominal: 3 retry attempts x 5s read timeout + 0.6s backoff = ~15.6s.
        // The upper bound leaves generous headroom for loaded CI runners
        // (20s proved too tight there - only ~4s slack) while still being
        // far below the 60s-PER-ATTEMPT default this test guards against.
        assertThat(elapsed)
                .as("a hung flight-service must fail within the configured window, not Feign's 60s default")
                .isLessThan(Duration.ofSeconds(40))
                .isGreaterThanOrEqualTo(Duration.ofSeconds(4)); // proves the 5s read timeout is what fired, not an instant connect failure
    }
}
