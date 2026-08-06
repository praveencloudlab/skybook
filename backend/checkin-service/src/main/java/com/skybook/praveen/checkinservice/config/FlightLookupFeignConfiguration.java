package com.skybook.praveen.checkinservice.config;

import com.skybook.praveen.security.ServiceTokenFeignInterceptor;
import com.skybook.praveen.security.ServiceTokenProvider;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;

/**
 * Feign configuration for check-in's flight lookup: a {@code ROLE_SERVICE}
 * token scoped to the {@code flight-service} audience, the same shape the
 * inventory client already uses.
 *
 * <p><b>Why it changed (GUEST_CHECKIN_MODULE.md, found on the first live
 * run):</b> this client used to propagate the CALLER's token. That worked
 * while every caller was a USER, then broke the moment a guest checked in -
 * flight-service does not accept guest tokens (and should not), so
 * "is this flight cancelled?" came back 401 and the check-in surfaced as a
 * 502. The propagation was wrong on principle, not just by consequence:
 * validating a flight's status is checkin-service asking a question <em>on
 * its own behalf</em> before it acts, not an action taken with the user's
 * authority. Its credential was already provisioned for this audience.
 *
 * <p>Nothing widens: flight reads are public shopping data at the edge, and
 * the caller's own authorization was already decided by the guard before this
 * call is ever made. NOT {@code @Configuration} and NOT component-scanned -
 * referenced only via {@code @FeignClient(configuration = ...)}.
 */
public class FlightLookupFeignConfiguration {

    @Bean
    public RequestInterceptor flightServiceTokenInterceptor(ServiceTokenProvider provider) {
        return new ServiceTokenFeignInterceptor(provider, "flight-service");
    }
}
