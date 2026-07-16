package com.skybook.praveen.bookingservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Declarative client for inventory-service's seat operations (Sprint 6).
 * Fixed base URL from application.yml - no service registry yet, same as
 * the flight-service client.
 */
@FeignClient(name = "inventory-service", url = "${inventory-service.base-url}",
        contextId = "bookingInventoryServiceFeignClient")
public interface InventoryServiceFeignClient {

    @PostMapping("/api/inventory/hold")
    InventoryHoldDetails holdSeat(@RequestBody InventorySeatCall call);

    // Atomic auto-hold (SEAT_SELECTION_MODULE.md §5.2): inventory picks and
    // holds a low-demand seat in the passenger's cabin under the flight lock.
    @PostMapping("/api/inventory/flights/{flightId}/holds/auto")
    InventoryHoldDetails autoHoldSeat(@PathVariable("flightId") Long flightId,
                                      @RequestBody InventorySeatCall call);

    @PostMapping("/api/inventory/release")
    InventoryHoldDetails releaseHold(@RequestBody InventorySeatCall call);

    @PostMapping("/api/reservations")
    InventoryReservationDetails reserveSeat(@RequestBody InventorySeatCall call);

    @PostMapping("/api/reservations/cancel")
    InventoryReservationDetails cancelReservation(@RequestBody InventorySeatCall call);
}
