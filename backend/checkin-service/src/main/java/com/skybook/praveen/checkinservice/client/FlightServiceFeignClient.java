package com.skybook.praveen.checkinservice.client;

import com.skybook.praveen.security.UserTokenFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Fixed base URL from application.yml. Propagates the incoming USER/ADMIN token
 * (§3.3) so this read still succeeds once flight-service enforces auth (§13
 * step 6).
 */
@FeignClient(name = "flight-service", url = "${flight-service.base-url}",
        contextId = "checkinFlightServiceFeignClient",
        configuration = UserTokenFeignConfiguration.class)
public interface FlightServiceFeignClient {

    @GetMapping("/api/flights/{id}")
    FlightCheckInDetails getFlight(@PathVariable Long id);
}
