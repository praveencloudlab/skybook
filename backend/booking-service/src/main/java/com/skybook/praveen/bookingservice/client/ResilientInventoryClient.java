package com.skybook.praveen.bookingservice.client;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Inner resilience bean (RESILIENCE_MODULE.md §5) - see ResilientFlightClient
 * for why the annotations live here and not on InventoryServiceClient.
 *
 * Deliberately NO @Retry on any method: all four operations are writes,
 * and retrying a timed-out write risks double side effects until
 * inventory-service certifies hold/reserve idempotency end-to-end (§6/§13).
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
}
