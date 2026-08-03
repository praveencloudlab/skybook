package com.skybook.praveen.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shape every service's @RestControllerAdvice hands back to the gateway
 * and, ultimately, to the browser. It is a record, so the value semantics are
 * what the API clients depend on.
 */
class ErrorResponseTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 7, 30, 9, 15, 30);

    @Test
    @DisplayName("all five components come back exactly as constructed")
    void componentsRoundTrip() {
        ErrorResponse response = new ErrorResponse(
                AT, 404, "Not Found", "Booking SB1234 not found", "/api/bookings/SB1234");

        assertThat(response.timestamp()).isEqualTo(AT);
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.error()).isEqualTo("Not Found");
        assertThat(response.message()).isEqualTo("Booking SB1234 not found");
        assertThat(response.path()).isEqualTo("/api/bookings/SB1234");
    }

    @Test
    @DisplayName("two responses with the same components are equal and hash alike")
    void equalityIsByValue() {
        ErrorResponse a = new ErrorResponse(AT, 400, "Bad Request", "Invalid PNR", "/api/bookings");
        ErrorResponse b = new ErrorResponse(AT, 400, "Bad Request", "Invalid PNR", "/api/bookings");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(new ErrorResponse(AT, 500, "Bad Request", "Invalid PNR", "/api/bookings"));
        assertThat(a).isNotEqualTo(null);
    }

    @Test
    @DisplayName("nulls are tolerated - a handler with no path or message still builds a response")
    void nullComponentsAreTolerated() {
        ErrorResponse response = new ErrorResponse(null, 500, null, null, null);

        assertThat(response.timestamp()).isNull();
        assertThat(response.error()).isNull();
        assertThat(response.message()).isNull();
        assertThat(response.path()).isNull();
        assertThat(response.status()).isEqualTo(500);
        assertThat(response).isEqualTo(new ErrorResponse(null, 500, null, null, null));
    }

    @Test
    @DisplayName("toString carries the status and message, so logged errors are readable")
    void toStringCarriesTheDetail() {
        ErrorResponse response = new ErrorResponse(AT, 409, "Conflict", "Seat 12A already held", "/api/seats");

        assertThat(response.toString())
                .contains("409")
                .contains("Conflict")
                .contains("Seat 12A already held")
                .contains("/api/seats");
    }

    @Test
    @DisplayName("the negative and zero status values are stored verbatim - no validation is implied")
    void statusIsStoredWithoutValidation() {
        assertThat(new ErrorResponse(AT, 0, "e", "m", "p").status()).isZero();
        assertThat(new ErrorResponse(AT, -1, "e", "m", "p").status()).isEqualTo(-1);
    }
}
