package com.skybook.praveen.bookingservice.client;

import com.skybook.praveen.security.UserTokenFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

/**
 * READ side of the inventory split (SECURITY_HARDENING_MODULE.md §3.3). A
 * cabin lookup feeds /quote for the requesting user, so it propagates the
 * incoming USER/ADMIN token ({@link UserTokenFeignConfiguration}). Kept a
 * SEPARATE interface (distinct contextId) from the command client so the
 * user-token interceptor can never leak onto a write.
 */
@FeignClient(name = "inventory-query", url = "${inventory-service.base-url}",
        contextId = "inventoryQuery",
        configuration = UserTokenFeignConfiguration.class)
public interface InventoryQueryFeignClient {

    // Which cabins the flight sells + seats left (§7/§11) - feeds /quote.
    @GetMapping("/api/inventory/flights/{flightId}/cabins")
    List<InventoryCabinDetails> getCabins(@PathVariable("flightId") Long flightId);
}
