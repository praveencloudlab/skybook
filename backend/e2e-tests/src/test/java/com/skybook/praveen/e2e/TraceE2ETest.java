package com.skybook.praveen.e2e;

import io.restassured.RestAssured;
import io.restassured.filter.Filter;
import io.restassured.response.Response;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
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

        // We MINT the trace id and send it as a W3C traceparent, rather than
        // completing a journey and then hunting for its trace in Tempo's search
        // API. Two reasons, both learned the hard way on a clean CI runner:
        //
        //  1. Tempo's search only covers FLUSHED blocks. On a fresh instance
        //     nothing has been flushed yet - its own log said
        //     "total_blocks=0 ... inspected_spans=0" - so a just-created trace is
        //     simply not findable, no matter how long you wait. Raising the
        //     timeout 60s -> 120s changed a failure at 62.7s into a failure at
        //     122.8s, which is what proved this was never a timing flake.
        //     Lookup BY ID (/api/traces/{id}) reads the ingesters too, so it sees
        //     data that has not been flushed.
        //  2. Knowing the id up front makes the assertion exact: we check the
        //     trace for THIS journey, not "some recent booking-service trace"
        //     that might belong to another test or an earlier session.
        String traceId = newTraceId();

        Filter traceparent = (spec, response, ctx) -> {
            spec.header("traceparent", "00-" + traceId + "-" + newSpanId() + "-01");
            return ctx.next(spec, response);
        };

        long bookingId;
        RestAssured.filters(traceparent);
        try {
            E2EUser passenger = Identities.newUser("traced");
            bookingId = Journey.confirmedBooking(passenger);
        } finally {
            // Leave the shared RestAssured config as we found it - every other
            // test in the suite uses these same statics.
            RestAssured.replaceFiltersWith(List.of());
        }

        // Awaitility throws on timeout, which would hide the diagnostic below -
        // so swallow it here and let the assertion report what was ACTUALLY in
        // the trace. "Expected both, saw [api-gateway, booking-service]" tells
        // you the Kafka hop broke; a bare ConditionTimeout tells you nothing.
        try {
            await("trace " + traceId + " to span booking-service and payment-service "
                    + "(booking " + bookingId + ")")
                    .atMost(Duration.ofSeconds(90))
                    .pollInterval(Duration.ofSeconds(2))
                    .until(() -> servicesIn(traceId).containsAll(
                            Set.of("booking-service", "payment-service")));
        } catch (ConditionTimeoutException expectedIfBroken) {
            // fall through to the assertion for a useful message
        }

        assertThat(servicesIn(traceId))
                .as("""
                        Trace %s did not contain both booking-service and payment-service.
                        They communicate over Kafka, so this means trace context is not
                        surviving the message boundary - each service would still be traced,
                        but you could no longer follow one booking end to end.""", traceId)
                .contains("booking-service", "payment-service");
    }

    /** A random, non-zero W3C trace id (32 lowercase hex chars). */
    private static String newTraceId() {
        return String.format("%016x%016x",
                ThreadLocalRandom.current().nextLong() | 1L,
                ThreadLocalRandom.current().nextLong() | 1L);
    }

    /** A random, non-zero W3C span id (16 lowercase hex chars). */
    private static String newSpanId() {
        return String.format("%016x", ThreadLocalRandom.current().nextLong() | 1L);
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
