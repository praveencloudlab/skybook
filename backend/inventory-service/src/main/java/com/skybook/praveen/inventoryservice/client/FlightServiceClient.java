package com.skybook.praveen.inventoryservice.client;

import com.skybook.praveen.inventoryservice.exception.FlightNotFoundForInventoryException;
import com.skybook.praveen.inventoryservice.exception.FlightServiceUnavailableException;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The only place inventory-service talks to another service directly. Thin
 * wrapper translating Feign's HTTP exceptions into this module's domain
 * exceptions so callers (InventoryFacade) never see Feign. Used to validate
 * a flight before inventory is created against it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlightServiceClient {

    private final FlightServiceFeignClient flightServiceFeignClient;

    public FlightDetails getFlight(Long flightId) {

        try {
            return flightServiceFeignClient.getFlight(flightId);

        } catch (FeignException.NotFound notFound) {
            throw new FlightNotFoundForInventoryException(flightId);

        } catch (FeignException unreachable) {
            log.error("Could not reach flight-service to validate flight {}", flightId, unreachable);
            throw new FlightServiceUnavailableException(flightId, unreachable);
        }
    }
}
