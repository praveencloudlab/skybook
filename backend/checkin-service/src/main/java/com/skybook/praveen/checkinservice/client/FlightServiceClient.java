package com.skybook.praveen.checkinservice.client;

import com.skybook.praveen.checkinservice.exception.FlightNotFoundForCheckInException;
import com.skybook.praveen.checkinservice.exception.FlightServiceUnavailableException;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Synchronous flight status/departure-time lookups (design doc section 9.2)
 * - checkin-service has no FlightEvent to consume (flight-service publishes
 * none yet), so live calls at the moments that matter (window open,
 * boarding) are how flight cancellation is noticed. Same translation
 * pattern as booking-service's FlightServiceClient.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlightServiceClient {

    private final FlightServiceFeignClient flightServiceFeignClient;

    public FlightCheckInDetails getFlight(Long flightId) {

        try {
            return flightServiceFeignClient.getFlight(flightId);

        } catch (FeignException.NotFound notFound) {
            throw new FlightNotFoundForCheckInException(flightId);

        } catch (FeignException unreachable) {
            log.error("Could not reach flight-service to validate flight {}", flightId, unreachable);
            throw new FlightServiceUnavailableException(flightId, unreachable);
        }
    }
}
