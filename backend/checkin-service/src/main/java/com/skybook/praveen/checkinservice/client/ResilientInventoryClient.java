package com.skybook.praveen.checkinservice.client;

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
 * @Retry only on the read (getReservationsByBooking); reserveSeat and
 * cancelReservation are writes - unretried until inventory-service
 * certifies idempotency end-to-end (§6/§13).
 */
@Component
@RequiredArgsConstructor
public class ResilientInventoryClient {

    private final InventoryServiceFeignClient feignClient;

    @Bulkhead(name = "inventory")
    @CircuitBreaker(name = "inventory")
    @Retry(name = "inventory-read")
    public List<SeatReservationDetails> getReservationsByBooking(Long bookingId) {
        return feignClient.getReservationsByBooking(bookingId);
    }

    @Bulkhead(name = "inventory")
    @CircuitBreaker(name = "inventory")
    public SeatReservationDetails reserveSeat(InventorySeatCall call) {
        return feignClient.reserveSeat(call);
    }

    @Bulkhead(name = "inventory")
    @CircuitBreaker(name = "inventory")
    public SeatReservationDetails cancelReservation(InventorySeatCall call) {
        return feignClient.cancelReservation(call);
    }
}
