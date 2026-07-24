package com.skybook.praveen.e2e;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Notification certification (E2E_CERTIFICATION_MODULE.md §10.2, build-order
 * step 5).
 *
 * <p>This is the only place notification-service is certified at all: it has no
 * business HTTP API (pure Kafka consumer), so the preflight cannot probe it.
 *
 * <p>The assertion is deliberately "an email <b>arrived</b>", not "an event was
 * published". Publishing an event proves booking/auth did their part; it says
 * nothing about whether the consumer ran, rendered, and actually sent - which is
 * what the customer experiences.
 */
@DisplayName("Notifications: a real email reaches the sink")
class NotificationE2ETest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssured.baseURI = E2EConfig.BASE_URL;
    }

    @Test
    @DisplayName("registering a passenger delivers a welcome email")
    void registrationSendsWelcomeEmail() {
        requireMailSink();

        E2EUser user = Identities.newUser("mail");

        Response delivered = awaitMailTo(user.email());

        assertThat(delivered.jsonPath().getInt("messages_count"))
                .as("""
                        No mail reached the sink for %s.
                        auth-service publishes an EmailEvent on registration and
                        notification-service consumes it - so this failing means the Kafka hop
                        or the send itself broke, not the registration (which returned 200).""",
                        user.email())
                .isPositive();

        String subject = delivered.jsonPath().getString("messages[0].Subject");
        assertThat(subject)
                .as("the welcome email should be recognisable to the passenger")
                .containsIgnoringCase("welcome");
    }

    /**
     * Polls the sink - delivery is asynchronous (Kafka hop, then SMTP).
     *
     * <p>The query goes through {@code queryParam}, NOT hand-encoded into the
     * path: RestAssured is configured to url-encode, so pre-encoding it produced
     * a double-encoded {@code %253A} that silently matched nothing. The mail had
     * genuinely arrived; only the search was wrong.
     */
    private Response awaitMailTo(String address) {
        Response[] last = new Response[1];

        await("an email addressed to " + address)
                .atMost(Journey.ASYNC_TIMEOUT)
                .pollInterval(java.time.Duration.ofSeconds(1))
                .until(() -> {
                    last[0] = RestAssured.given()
                            .baseUri(E2EConfig.MAIL_URL)
                            .queryParam("query", "to:" + address)
                            .when()
                            .get("/api/v1/search");
                    return last[0].statusCode() == 200
                            && last[0].jsonPath().getInt("messages_count") > 0;
                });
        return last[0];
    }

    private void requireMailSink() {
        try {
            int status = RestAssured.given()
                    .baseUri(E2EConfig.MAIL_URL)
                    .when()
                    .get("/api/v1/messages")
                    .statusCode();
            if (status != 200) {
                throw new IllegalStateException("sink returned " + status);
            }
        } catch (Exception e) {
            throw new IllegalStateException("""
                    No mail sink at %s.
                    Remediation: bring the fleet up WITH the e2e override, which adds it:
                      docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d
                    (%s)""".formatted(E2EConfig.MAIL_URL, e.getMessage()));
        }
    }
}
