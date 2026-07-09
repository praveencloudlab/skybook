package com.skybook.praveen.checkinservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Fixed base URL from application.yml - no service registry yet, same as booking-service's client. */
@FeignClient(name = "flight-service", url = "${flight-service.base-url}",
        contextId = "checkinFlightServiceFeignClient")
public interface FlightServiceFeignClient {

    @GetMapping("/api/flights/{id}")
    FlightCheckInDetails getFlight(@PathVariable Long id);
}
