package com.skybook.praveen.checkinservice.client;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Inner resilience bean (RESILIENCE_MODULE.md §5): annotations live here,
 * on a bean that still throws raw FeignExceptions, because the AOP aspect
 * wraps the outside of the annotated method - exception filtering only
 * works against untranslated exceptions, and CallNotPermittedException/
 * BulkheadFullException can only be translated by a caller outside this
 * proxy (FlightServiceClient). Do not add try/catch here.
 */
@Component
@RequiredArgsConstructor
public class ResilientFlightClient {

    private final FlightServiceFeignClient feignClient;

    @Bulkhead(name = "flight")
    @CircuitBreaker(name = "flight")
    @Retry(name = "flight-read")
    public FlightCheckInDetails getFlight(Long flightId) {
        return feignClient.getFlight(flightId);
    }
}
