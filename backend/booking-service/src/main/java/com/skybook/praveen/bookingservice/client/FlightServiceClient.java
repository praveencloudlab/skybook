package com.skybook.praveen.bookingservice.client;

import com.skybook.praveen.bookingservice.exception.FlightNotFoundForBookingException;
import com.skybook.praveen.bookingservice.exception.FlightServiceUnavailableException;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The only place booking-service talks to another service directly (docs
 * section 8). Thin wrapper around FlightServiceFeignClient that translates
 * Feign's generic HTTP exceptions into this module's own domain exceptions,
 * so callers (BookingFacade) never need to know Feign is involved. Used to
 * validate a flight before a booking is created against it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlightServiceClient {

    private final FlightServiceFeignClient flightServiceFeignClient;

    public FlightDetails getFlight(Long flightId) {

        try {
            return flightServiceFeignClient.getFlight(flightId);

        } catch (FeignException.NotFound notFound) {
            throw new FlightNotFoundForBookingException(flightId);

        } catch (FeignException unreachable) {
            log.error("Could not reach flight-service to validate flight {}", flightId, unreachable);
            throw new FlightServiceUnavailableException(flightId, unreachable);
        }
    }
}
