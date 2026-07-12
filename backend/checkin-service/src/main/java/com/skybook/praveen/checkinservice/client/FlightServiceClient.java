package com.skybook.praveen.checkinservice.client;

import com.skybook.praveen.checkinservice.exception.FlightNotFoundForCheckInException;
import com.skybook.praveen.checkinservice.exception.FlightServiceUnavailableException;
import feign.FeignException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Synchronous flight status/departure-time lookups (design doc section 9.2)
 * - checkin-service has no FlightEvent to consume (flight-service publishes
 * none yet), so live calls at the moments that matter (window open,
 * boarding) are how flight cancellation is noticed. Domain boundary of the
 * two-bean resilience split (RESILIENCE_MODULE.md §5), same pattern as
 * booking-service's FlightServiceClient.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlightServiceClient {

    private final ResilientFlightClient resilientFlightClient;

    public FlightCheckInDetails getFlight(Long flightId) {

        try {
            return resilientFlightClient.getFlight(flightId);

        } catch (FeignException.NotFound notFound) {
            throw new FlightNotFoundForCheckInException(flightId);

        } catch (CallNotPermittedException | BulkheadFullException fastFail) {
            log.warn("flight-service call rejected without attempt ({}), flight {}",
                    fastFail.getClass().getSimpleName(), flightId);
            throw new FlightServiceUnavailableException(flightId, fastFail);

        } catch (FeignException unreachable) {
            log.error("Could not reach flight-service to validate flight {}", flightId, unreachable);
            throw new FlightServiceUnavailableException(flightId, unreachable);
        }
    }
}
