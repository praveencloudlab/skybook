package com.skybook.praveen.bookingservice.client;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Inner resilience bean (RESILIENCE_MODULE.md §5): the Resilience4j
 * annotations MUST live on a bean whose methods still throw raw
 * FeignExceptions - the AOP aspect wraps the outside of the annotated
 * method, so record-exceptions/retry-exceptions filtering only works
 * here, and CallNotPermittedException/BulkheadFullException are thrown
 * before the method body runs, translatable only by a caller OUTSIDE
 * this proxy (FlightServiceClient, the domain boundary). Do not add
 * try/catch or exception translation to this class.
 */
@Component
@RequiredArgsConstructor
public class ResilientFlightClient {

    private final FlightServiceFeignClient feignClient;

    @Bulkhead(name = "flight")
    @CircuitBreaker(name = "flight")
    @Retry(name = "flight-read")
    public FlightDetails getFlight(Long flightId) {
        return feignClient.getFlight(flightId);
    }
}
