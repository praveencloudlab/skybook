package com.skybook.praveen.checkinservice.client;

import com.skybook.praveen.checkinservice.config.FlightLookupFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Fixed base URL from application.yml. Carries a ROLE_SERVICE token scoped to
 * flight-service: this lookup is check-in validating a flight before it acts,
 * on its own behalf - not an action taken with the caller's authority. See
 * {@link FlightLookupFeignConfiguration} for the live failure that corrected
 * the original user-token propagation.
 */
@FeignClient(name = "flight-service", url = "${flight-service.base-url}",
        contextId = "checkinFlightServiceFeignClient",
        configuration = FlightLookupFeignConfiguration.class)
public interface FlightServiceFeignClient {

    @GetMapping("/api/flights/{id}")
    FlightCheckInDetails getFlight(@PathVariable Long id);
}
