package com.skybook.praveen.inventoryservice.client;

import com.skybook.praveen.inventoryservice.exception.FlightNotFoundForInventoryException;
import com.skybook.praveen.inventoryservice.exception.FlightServiceUnavailableException;
import feign.FeignException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The only place inventory-service talks to another service directly.
 * Domain boundary of the two-bean resilience split (RESILIENCE_MODULE.md
 * §5): delegates to ResilientFlightClient (aspects against raw Feign
 * exceptions) and translates every infrastructure failure - HTTP, open
 * circuit, saturated bulkhead - into this module's domain exceptions so
 * callers (InventoryFacade) never see Feign or Resilience4j.
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
            throw new FlightNotFoundForInventoryException(flightId);

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
