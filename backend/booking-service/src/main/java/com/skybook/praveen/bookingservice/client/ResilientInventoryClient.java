package com.skybook.praveen.bookingservice.client;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Inner resilience bean (RESILIENCE_MODULE.md §5) - see ResilientFlightClient
 * for why the annotations live here and not on InventoryServiceClient.
 *
 * Deliberately NO @Retry on the write operations: retrying a timed-out write
 * risks double side effects until inventory-service certifies hold/reserve
 * idempotency end-to-end (§6/§13). The read-only cabin lookup DOES retry -
 * same rationale as flight-read.
 */
@Component
@RequiredArgsConstructor
public class ResilientInventoryClient {

    private final InventoryServiceFeignClient feignClient;

    @Bulkhead(name = "inventory")
    @CircuitBreaker(name = "inventory")
    public InventoryHoldDetails holdSeat(InventorySeatCall call) {
        return feignClient.holdSeat(call);
    }

    @Bulkhead(name = "inventory")
    @CircuitBreaker(name = "inventory")
    public InventoryHoldDetails autoHoldSeat(Long flightId, InventorySeatCall call) {
        return feignClient.autoHoldSeat(flightId, call);
    }

    @Bulkhead(name = "inventory")
    @CircuitBreaker(name = "inventory")
    public InventoryHoldDetails releaseHold(InventorySeatCall call) {
        return feignClient.releaseHold(call);
    }

    @Bulkhead(name = "inventory")
    @CircuitBreaker(name = "inventory")
    public InventoryReservationDetails reserveSeat(InventorySeatCall call) {
        return feignClient.reserveSeat(call);
    }

    @Bulkhead(name = "inventory")
    @CircuitBreaker(name = "inventory")
    public InventoryReservationDetails cancelReservation(InventorySeatCall call) {
        return feignClient.cancelReservation(call);
    }

    @Bulkhead(name = "inventory")
    @CircuitBreaker(name = "inventory")
    @Retry(name = "inventory-read")
    public List<InventoryCabinDetails> getCabins(Long flightId) {
        return feignClient.getCabins(flightId);
    }
}
