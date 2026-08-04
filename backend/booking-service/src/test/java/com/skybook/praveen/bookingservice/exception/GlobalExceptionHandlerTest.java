package com.skybook.praveen.bookingservice.exception;

import com.skybook.praveen.common.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The advice is the only thing standing between a domain exception and a 500.
 * Each test pins one exception to one status, because these mappings are the
 * API contract the frontend branches on - a 409 it can retry, a 502 it must
 * not.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static HttpServletRequest requestTo(String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        return request;
    }

    private static void assertResponse(ResponseEntity<ErrorResponse> response, HttpStatus expected,
                                       String uri) {
        assertThat(response.getStatusCode()).isEqualTo(expected);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(expected.value());
        assertThat(response.getBody().error()).isEqualTo(expected.getReasonPhrase());
        assertThat(response.getBody().path()).isEqualTo(uri);
        assertThat(response.getBody().timestamp()).isNotNull();
    }

    @Nested
    @DisplayName("404 - the thing genuinely is not there")
    class NotFound {

        @Test
        @DisplayName("an unknown booking id is a 404 naming the id")
        void unknownBookingId() {
            ResponseEntity<ErrorResponse> response = handler.handleBookingNotFoundException(
                    new BookingNotFoundException(77L), requestTo("/api/bookings/77"));

            assertResponse(response, HttpStatus.NOT_FOUND, "/api/bookings/77");
            assertThat(response.getBody().message()).contains("77");
        }

        @Test
        @DisplayName("an unknown passenger row on a real booking is also a 404")
        void unknownPassengerRow() {
            ResponseEntity<ErrorResponse> response = handler.handleBookingPassengerNotFoundException(
                    new BookingPassengerNotFoundException(77L, 11L),
                    requestTo("/api/bookings/77/passengers/11/check-in"));

            assertResponse(response, HttpStatus.NOT_FOUND, "/api/bookings/77/passengers/11/check-in");
            assertThat(response.getBody().message()).contains("11", "77");
        }

        @Test
        @DisplayName("a flight that flight-service does not know is the caller's mistake, not an outage")
        void unknownFlight() {
            ResponseEntity<ErrorResponse> response = handler.handleFlightNotFoundForBookingException(
                    new FlightNotFoundForBookingException(9L), requestTo("/api/bookings"));

            assertResponse(response, HttpStatus.NOT_FOUND, "/api/bookings");
            assertThat(response.getBody().message()).contains("9");
        }
    }

    @Nested
    @DisplayName("502 - a dependency is down, the request was fine")
    class BadGateway {

        @Test
        @DisplayName("an unreachable flight-service is a 502, never a 404 or a 500")
        void unreachableFlightService() {
            ResponseEntity<ErrorResponse> response = handler.handleFlightServiceUnavailableException(
                    new FlightServiceUnavailableException(9L, new IllegalStateException("timeout")),
                    requestTo("/api/bookings"));

            assertResponse(response, HttpStatus.BAD_GATEWAY, "/api/bookings");
            assertThat(response.getBody().message()).contains("flight-service");
        }

        @Test
        @DisplayName("an unreachable inventory-service is a 502 - seats are never sold blind")
        void unreachableInventoryService() {
            ResponseEntity<ErrorResponse> response = handler.handleInventoryServiceUnavailableException(
                    new InventoryServiceUnavailableException(9L, new IllegalStateException("circuit open")),
                    requestTo("/api/bookings"));

            assertResponse(response, HttpStatus.BAD_GATEWAY, "/api/bookings");
            assertThat(response.getBody().message()).contains("inventory-service");
        }
    }

    @Nested
    @DisplayName("409 - the request collides with the current state")
    class Conflict {

        @Test
        @DisplayName("a taken seat is a 409 carrying inventory's own reason")
        void takenSeat() {
            ResponseEntity<ErrorResponse> response = handler.handleSeatUnavailableException(
                    new SeatUnavailableException(9L, "12A", "already held"),
                    requestTo("/api/bookings"));

            assertResponse(response, HttpStatus.CONFLICT, "/api/bookings");
            assertThat(response.getBody().message()).contains("12A", "already held");
        }

        @Test
        @DisplayName("a lost optimistic-lock race is rewritten into advice the user can act on")
        void lostOptimisticLockRace() {
            ResponseEntity<ErrorResponse> response = handler.handleOptimisticLockingFailureException(
                    new ObjectOptimisticLockingFailureException("Booking", 77L),
                    requestTo("/api/bookings/77/cancel"));

            assertResponse(response, HttpStatus.CONFLICT, "/api/bookings/77/cancel");
            // The raw Hibernate wording would mean nothing to a passenger.
            assertThat(response.getBody().message())
                    .isEqualTo("This record was modified by another request. Please reload and try again.");
        }

        @Test
        @DisplayName("an illegal state transition is a 409 with the rule that refused it")
        void illegalStateTransition() {
            ResponseEntity<ErrorResponse> response = handler.handleIllegalStateException(
                    new IllegalStateException("Booking must be CONFIRMED to check in"),
                    requestTo("/api/bookings/77/passengers/11/check-in"));

            assertResponse(response, HttpStatus.CONFLICT, "/api/bookings/77/passengers/11/check-in");
            assertThat(response.getBody().message()).isEqualTo("Booking must be CONFIRMED to check in");
        }
    }

    @Nested
    @DisplayName("400 - the request itself is wrong")
    class BadRequest {

        @Test
        @DisplayName("an illegal argument is a 400 carrying the offending rule")
        void illegalArgument() {
            ResponseEntity<ErrorResponse> response = handler.handleIllegalArgumentException(
                    new IllegalArgumentException("segmentIndex 0 cannot be cancelled alone"),
                    requestTo("/api/bookings/77/segments/0/cancel"));

            assertResponse(response, HttpStatus.BAD_REQUEST, "/api/bookings/77/segments/0/cancel");
            assertThat(response.getBody().message()).isEqualTo("segmentIndex 0 cannot be cancelled alone");
        }

        @Test
        @DisplayName("every rejected field is named, so the form can highlight all of them at once")
        void everyRejectedFieldIsNamed() throws Exception {
            BindingResult binding = new BeanPropertyBindingResult(new Object(), "createBookingRequest");
            binding.addError(new org.springframework.validation.FieldError(
                    "createBookingRequest", "flightId", "flightId is required"));
            binding.addError(new org.springframework.validation.FieldError(
                    "createBookingRequest", "passengers", "at least one passenger is required"));

            ResponseEntity<ErrorResponse> response = handler.handleValidationException(
                    methodArgumentNotValid(binding), requestTo("/api/bookings"));

            assertResponse(response, HttpStatus.BAD_REQUEST, "/api/bookings");
            assertThat(response.getBody().message())
                    .isEqualTo("flightId: flightId is required, "
                            + "passengers: at least one passenger is required");
        }

        @Test
        @DisplayName("a rejection with no field errors still produces a 400 rather than a 500")
        void aRejectionWithNoFieldErrorsIsStillA400() throws Exception {
            ResponseEntity<ErrorResponse> response = handler.handleValidationException(
                    methodArgumentNotValid(new BeanPropertyBindingResult(new Object(), "request")),
                    requestTo("/api/bookings"));

            assertResponse(response, HttpStatus.BAD_REQUEST, "/api/bookings");
            assertThat(response.getBody().message()).isEmpty();
        }

        /** MethodArgumentNotValidException needs a real MethodParameter - any method will do. */
        private MethodArgumentNotValidException methodArgumentNotValid(BindingResult binding)
                throws NoSuchMethodException {
            Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("sampleEndpoint", String.class);
            return new MethodArgumentNotValidException(new MethodParameter(method, 0), binding);
        }
    }

    @SuppressWarnings("unused")
    private void sampleEndpoint(String body) {
        // Signature target for MethodParameter - never invoked.
    }
}
