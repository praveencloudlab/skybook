package com.skybook.praveen.bookingservice.client;

import com.skybook.praveen.bookingservice.exception.FlightNotFoundForBookingException;
import com.skybook.praveen.bookingservice.exception.FlightServiceUnavailableException;
import feign.FeignException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The only place booking-service talks to another service directly (docs
 * section 8). Domain boundary of the two-bean resilience split
 * (RESILIENCE_MODULE.md §5): delegates to ResilientFlightClient (where the
 * Resilience4j aspects run against raw Feign exceptions) and translates
 * every infrastructure failure - HTTP, open circuit, saturated bulkhead -
 * into this module's own domain exceptions, so callers (BookingFacade)
 * never need to know Feign or Resilience4j is involved.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlightServiceClient {

    private final ResilientFlightClient resilientFlightClient;

    public FlightDetails getFlight(Long flightId) {

        try {
            return resilientFlightClient.getFlight(flightId);

        } catch (FeignException.NotFound notFound) {
            throw new FlightNotFoundForBookingException(flightId);

        } catch (CallNotPermittedException | BulkheadFullException fastFail) {
            // Thrown by the aspects BEFORE any call is attempted - circuit
            // open or too many concurrent calls in flight. Same contract as
            // any other unavailability, just decided in microseconds.
            log.warn("flight-service call rejected without attempt ({}), flight {}",
                    fastFail.getClass().getSimpleName(), flightId);
            throw new FlightServiceUnavailableException(flightId, fastFail);

        } catch (FeignException unreachable) {
            log.error("Could not reach flight-service to validate flight {}", flightId, unreachable);
            throw new FlightServiceUnavailableException(flightId, unreachable);
        }
    }
}
