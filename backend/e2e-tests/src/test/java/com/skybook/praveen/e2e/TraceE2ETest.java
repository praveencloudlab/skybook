package com.skybook.praveen.e2e;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Distributed-trace certification (E2E_CERTIFICATION_MODULE.md §7, build-order
 * step 10).
 *
 * <p>Closes the gap {@code OBSERVABILITY_MODULE.md} left open: the Kafka-hop
 * trace was only ever verified "by mechanism", because at the time there were no
 * seeded flights to complete a real booking with. There are now.
 *
 * <p>What makes this worth asserting is the <b>Kafka hop specifically</b>.
 * Trace context riding an HTTP call is table stakes; carrying it across an
 * asynchronous message boundary is the part that silently breaks, and when it
 * does you lose the ability to follow a booking through payment at exactly the
 * moment you need it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Observability: one trace follows a booking across the Kafka hop")
class TraceE2ETest {

    private static final Pattern SERVICE_NAME = Pattern.compile(
            "\"key\":\"service\\.name\",\"value\":\\{\"stringValue\":\"([a-z-]+)\"");

    @BeforeAll
    void configureRestAssured() {
        RestAssured.baseURI = E2EConfig.BASE_URL;
    }

    @Test
    @DisplayName("a completed journey produces one trace spanning gateway, booking and payment")
    void journeyIsTracedAcrossServices() {
        requireTempo();

        // Generate fresh telemetry rather than trusting whatever is already in
        // Tempo - otherwise this could pass on a trace from an earlier session.
        E2EUser passenger = Identities.newUser("traced");
        long bookingId = Journey.confirmedBooking(passenger);

        Set<String>[] best = new Set[]{Set.of()};

        // 120s, not 60s. Tempo's ingest+index lag is the slowest thing this suite
        // waits on, and 60s sat right on the edge: locally it timed out at 64s
        // just after a service restart and then passed in 14s, and on a CI runner
        // it timed out at 62.7s while every other test passed. A trace assertion
        // that fails on a cold or busy Tempo trains people to ignore it, which is
        // worse than a slow test - the journey and the trace were correct both times.
        await("a trace spanning api-gateway -> booking-service -> (Kafka) -> payment-service "
                + "for booking " + bookingId)
                .atMost(Duration.ofSeconds(120))
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> {
                    for (String traceId : recentTraceIds()) {
                        Set<String> services = servicesIn(traceId);
                        if (services.size() > best[0].size()) {
                            best[0] = services;
                        }
                        if (services.contains("booking-service")
                                && services.contains("payment-service")) {
                            return true;
                        }
                    }
                    return false;
                });

        assertThat(best[0])
                .as("""
                        No single trace contained both booking-service and payment-service.
                        They communicate over Kafka, so this means trace context is not
                        surviving the message boundary - each service would still be traced,
                        but you could no longer follow one booking end to end.
                        Widest trace seen: %s""", best[0])
                .contains("booking-service", "payment-service");
    }

    /** Trace ids Tempo has seen for booking-service, newest first. */
    private List<String> recentTraceIds() {
        return RestAssured.given()
                .baseUri(E2EConfig.TEMPO_URL)
                .queryParam("tags", "service.name=booking-service")
                .queryParam("limit", 30)
                .when()
                .get("/api/search")
                .jsonPath()
                .getList("traces.traceID", String.class);
    }

    /**
     * Distinct {@code service.name}s in a trace.
     *
     * <p>Read by regex over the raw body on purpose: Tempo returns OTLP's deeply
     * nested {@code batches[].resource.attributes[]} shape, and walking that with
     * JsonPath is far more brittle than matching the one attribute we care about.
     */
    private Set<String> servicesIn(String traceId) {
        Response trace = RestAssured.given()
                .baseUri(E2EConfig.TEMPO_URL)
                .when()
                .get("/api/traces/" + traceId);

        Set<String> services = new LinkedHashSet<>();
        if (trace.statusCode() != 200) {
            return services;
        }
        Matcher matcher = SERVICE_NAME.matcher(trace.asString());
        while (matcher.find()) {
            services.add(matcher.group(1));
        }
        return services;
    }

    private void requireTempo() {
        try {
            int status = RestAssured.given()
                    .baseUri(E2EConfig.TEMPO_URL)
                    .when()
                    .get("/ready")
                    .statusCode();
            if (status >= 500) {
                throw new IllegalStateException("Tempo not ready (" + status + ")");
            }
        } catch (Exception e) {
            throw new IllegalStateException("""
                    No Tempo at %s - the trace assertion cannot run.
                    Remediation: `docker compose up -d tempo` (it is part of the observability
                    stack in the default compose file). (%s)"""
                    .formatted(E2EConfig.TEMPO_URL, e.getMessage()));
        }
    }
}
