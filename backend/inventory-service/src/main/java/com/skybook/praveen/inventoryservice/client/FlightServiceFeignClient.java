package com.skybook.praveen.inventoryservice.client;

import com.skybook.praveen.security.UserTokenFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * `name` is the logical service id, `url` pins the fixed base URL from
 * application.yml (no service registry yet).
 *
 * Identity (SECURITY_HARDENING_MODULE.md §3.3): inventory->flight runs inside
 * the ADMIN-originated inventory-creation flow, so it PROPAGATES the caller's
 * token ({@link UserTokenFeignConfiguration}). If it is ever reused from a
 * scheduler/Kafka origin it would need an inventory-service->flight-service
 * service token instead - flagged for that day, not needed in v1.
 */
@FeignClient(name = "flight-service", url = "${flight-service.base-url}",
        contextId = "inventoryFlightServiceFeignClient",
        configuration = UserTokenFeignConfiguration.class)
public interface FlightServiceFeignClient {

    @GetMapping("/api/flights/{id}")
    FlightDetails getFlight(@PathVariable("id") Long id);
}
