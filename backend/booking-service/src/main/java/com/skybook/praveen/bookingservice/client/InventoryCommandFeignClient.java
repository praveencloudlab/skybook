package com.skybook.praveen.bookingservice.client;

import com.skybook.praveen.bookingservice.config.InventoryCommandFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * WRITE side of the inventory split (SECURITY_HARDENING_MODULE.md §3.3). The
 * seat mutations the authorization matrix denies to a USER, so this client
 * attaches booking-service's own {@code ROLE_SERVICE} token targeting
 * {@code inventory-service} ({@link InventoryCommandFeignConfiguration}). A
 * SEPARATE interface (distinct contextId) from the query client so the service
 * token and the user token never share a client.
 */
@FeignClient(name = "inventory-command", url = "${inventory-service.base-url}",
        contextId = "inventoryCommand",
        configuration = InventoryCommandFeignConfiguration.class)
public interface InventoryCommandFeignClient {

    @PostMapping("/api/inventory/hold")
    InventoryHoldDetails holdSeat(@RequestBody InventorySeatCall call);

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
