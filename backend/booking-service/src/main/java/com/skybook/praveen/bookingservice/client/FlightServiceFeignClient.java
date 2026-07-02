package com.skybook.praveen.bookingservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Declarative Feign replacement for the old RestClient-based call to
 * flight-service's GET /api/flights/{id}. `name` doubles as the logical
 * service id (would matter if this were resolved via a discovery client
 * later); `url` overrides it with the fixed base URL from application.yml
 * since there's no service registry (Eureka/Consul) in this project yet.
 */
@FeignClient(name = "flight-service", url = "${flight-service.base-url}")
public interface FlightServiceFeignClient {

    @GetMapping("/api/flights/{id}")
    FlightDetails getFlight(@PathVariable("id") Long id);
}
