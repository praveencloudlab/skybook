package com.skybook.praveen.bookingservice.client;

import com.skybook.praveen.bookingservice.config.FlightCommandFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Service-token variant of {@link FlightServiceFeignClient} for best-effort
 * event enrichment that may run off the request thread (Kafka-driven booking
 * confirmation, §3.3/§4.2). Same endpoint, but carries a {@code ROLE_SERVICE}
 * token (aud=flight-service) instead of propagating an incoming user token,
 * which does not exist on a Kafka consumer thread.
 */
@FeignClient(name = "flight-command", url = "${flight-service.base-url}",
        configuration = FlightCommandFeignConfiguration.class)
public interface FlightCommandFeignClient {

    @GetMapping("/api/flights/{id}")
    FlightDetails getFlight(@PathVariable("id") Long id);
}
