package com.skybook.praveen.flightservice.exception;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire-level error contract every caller (gateway, frontend, the other
 * services) parses: status code, reason phrase, message and the request path.
 * Each handler is checked directly rather than through MockMvc so the
 * ErrorResponse body itself is asserted field by field.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return request;
    }

    @SuppressWarnings("unused")
    private static void validatedEndpoint(String flightNumber) {
        // Reflection target used to build a real MethodArgumentNotValidException.
    }

    @Test
    void missingFlightIsNotFound() {
        ResponseEntity<ErrorResponse> response = handler.handleFlightNotFoundException(
                new FlightNotFoundException(42L), request("/api/flights/42"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(404);
        assertThat(body.error()).isEqualTo("Not Found");
        assertThat(body.message()).isEqualTo("Flight not found with id: 42");
        assertThat(body.path()).isEqualTo("/api/flights/42");
        assertThat(body.timestamp()).isBeforeOrEqualTo(LocalDateTime.now());
    }

    @Test
    void missingScheduleIsNotFound() {
        ResponseEntity<ErrorResponse> response = handler.handleFlightScheduleNotFoundException(
                new FlightScheduleNotFoundException(7L), request("/api/flight-schedules/7"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Flight schedule not found with id: 7");
        assertThat(response.getBody().path()).isEqualTo("/api/flight-schedules/7");
    }

    @Test
    void concurrentModificationIsAConflictWithAReloadHint() {
        ResponseEntity<ErrorResponse> response = handler.handleOptimisticLockingFailureException(
                new ObjectOptimisticLockingFailureException("flights", 1L),
                request("/api/flights/1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(409);
        assertThat(response.getBody().error()).isEqualTo("Conflict");
        // The raw Hibernate message never reaches the caller.
        assertThat(response.getBody().message())
                .isEqualTo("This record was modified by another request. Please reload and try again.");
    }

    @Test
    void illegalStateIsAConflict() {
        ResponseEntity<ErrorResponse> response = handler.handleIllegalStateException(
                new IllegalStateException("Only paused schedules can be resumed"),
                request("/api/flight-schedules/7/resume"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(409);
        assertThat(response.getBody().message()).isEqualTo("Only paused schedules can be resumed");
    }

    @Test
    void illegalArgumentIsABadRequest() {
        ResponseEntity<ErrorResponse> response = handler.handleIllegalArgumentException(
                new IllegalArgumentException("Calendar range must not exceed 124 days"),
                request("/api/flights/calendar"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().error()).isEqualTo("Bad Request");
        assertThat(response.getBody().message()).isEqualTo("Calendar range must not exceed 124 days");
    }

    @Test
    void beanValidationFailuresAreFlattenedIntoOneFieldByFieldMessage() throws NoSuchMethodException {
        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("validatedEndpoint", String.class), 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "flightNumber", "Flight number is required"));
        bindingResult.addError(new FieldError("request", "airlineCode", "Airline code is required"));

        ResponseEntity<ErrorResponse> response = handler.handleValidationException(
                new MethodArgumentNotValidException(parameter, bindingResult),
                request("/api/flights"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .isEqualTo("flightNumber: Flight number is required, airlineCode: Airline code is required");
        assertThat(response.getBody().path()).isEqualTo("/api/flights");
    }
}
