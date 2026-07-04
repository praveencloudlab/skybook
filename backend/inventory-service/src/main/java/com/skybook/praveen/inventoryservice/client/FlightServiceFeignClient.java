package com.skybook.praveen.inventoryservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Same setup as booking-service's Feign client: `name` is the logical
 * service id, `url` pins the fixed base URL from application.yml since
 * there's no service registry in this project yet.
 */
@FeignClient(name = "flight-service", url = "${flight-service.base-url}", contextId = "inventoryFlightServiceFeignClient")
public interface FlightServiceFeignClient {

    @GetMapping("/api/flights/{id}")
    FlightDetails getFlight(@PathVariable("id") Long id);
}
