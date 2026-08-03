package com.skybook.praveen.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * auth-service's transactional mail envelope on skybook.email.events. Four
 * fields, no optional shape - if any of them is null the recipient gets a
 * blank email, so the round trips are asserted plainly.
 */
class EmailEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("the builder carries every field through to the getters")
    void builderCarriesEveryField() {
        EmailEvent event = EmailEvent.builder()
                .to("praveen@example.com")
                .subject("Welcome to SkyBook")
                .body("Your account is ready.")
                .type(EmailType.REGISTRATION_SUCCESS)
                .build();

        assertThat(event.getTo()).isEqualTo("praveen@example.com");
        assertThat(event.getSubject()).isEqualTo("Welcome to SkyBook");
        assertThat(event.getBody()).isEqualTo("Your account is ready.");
        assertThat(event.getType()).isEqualTo(EmailType.REGISTRATION_SUCCESS);
    }

    @Test
    @DisplayName("the all-args constructor matches the declared field order")
    void allArgsConstructorMatchesFieldOrder() {
        EmailEvent event = new EmailEvent(
                "praveen@example.com", "Reset your password", "Use this link...", EmailType.FORGOT_PASSWORD);

        assertThat(event.getTo()).isEqualTo("praveen@example.com");
        assertThat(event.getSubject()).isEqualTo("Reset your password");
        assertThat(event.getBody()).isEqualTo("Use this link...");
        assertThat(event.getType()).isEqualTo(EmailType.FORGOT_PASSWORD);
    }

    @Test
    @DisplayName("the no-args constructor leaves everything null for the deserializer")
    void noArgsConstructorLeavesEverythingNull() {
        EmailEvent event = new EmailEvent();

        assertThat(event.getTo()).isNull();
        assertThat(event.getSubject()).isNull();
        assertThat(event.getBody()).isNull();
        assertThat(event.getType()).isNull();
    }

    @Test
    @DisplayName("setters rewrite each field")
    void settersRewriteFields() {
        EmailEvent event = new EmailEvent();
        event.setTo("ann@example.com");
        event.setSubject("Reset your password");
        event.setBody("Use this link...");
        event.setType(EmailType.FORGOT_PASSWORD);

        assertThat(event.getTo()).isEqualTo("ann@example.com");
        assertThat(event.getSubject()).isEqualTo("Reset your password");
        assertThat(event.getBody()).isEqualTo("Use this link...");
        assertThat(event.getType()).isEqualTo(EmailType.FORGOT_PASSWORD);
    }

    @Test
    @DisplayName("nulls are tolerated at construction - the contract validates nothing itself")
    void nullsAreTolerated() {
        EmailEvent event = EmailEvent.builder().to(null).subject(null).body(null).type(null).build();

        assertThat(event).isNotNull();
        assertThat(event.getTo()).isNull();
        assertThat(event.getType()).isNull();
    }

    @Test
    @DisplayName("the envelope survives a JSON round trip")
    void jsonRoundTripPreservesEveryField() throws Exception {
        EmailEvent original = EmailEvent.builder()
                .to("praveen@example.com")
                .subject("Welcome to SkyBook")
                .body("Your account is ready.")
                .type(EmailType.REGISTRATION_SUCCESS)
                .build();

        EmailEvent parsed = MAPPER.readValue(MAPPER.writeValueAsString(original), EmailEvent.class);

        assertThat(parsed.getTo()).isEqualTo("praveen@example.com");
        assertThat(parsed.getSubject()).isEqualTo("Welcome to SkyBook");
        assertThat(parsed.getBody()).isEqualTo("Your account is ready.");
        assertThat(parsed.getType()).isEqualTo(EmailType.REGISTRATION_SUCCESS);
    }

    @Test
    @DisplayName("the type is serialized by name, so a renamed constant would break consumers")
    void typeIsSerializedByName() throws Exception {
        String json = MAPPER.writeValueAsString(
                EmailEvent.builder().type(EmailType.FORGOT_PASSWORD).build());

        assertThat(json).contains("\"type\":\"FORGOT_PASSWORD\"");
    }
}
