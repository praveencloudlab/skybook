package com.skybook.praveen.bookingservice.config;

import com.skybook.praveen.security.ServiceTokenFeignInterceptor;
import com.skybook.praveen.security.ServiceTokenProvider;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;

/**
 * Feign configuration for booking-service's flight SERVICE-token client
 * (SECURITY_HARDENING_MODULE.md §3.3): attaches a {@code ROLE_SERVICE} token
 * scoped to the {@code flight-service} audience.
 *
 * <p>Used only for event enrichment that can run off the request thread - the
 * Kafka-driven {@code PAYMENT_SUCCEEDED -> confirm} path has no incoming user
 * token to propagate, so the user-token flight client would 401 and the
 * confirmation email would silently lose its route details. NOT
 * {@code @Configuration} / not component-scanned - referenced only via
 * {@code @FeignClient(configuration = ...)} so the interceptor stays on this
 * one client and never leaks onto the user-token query client.
 */
public class FlightCommandFeignConfiguration {

    @Bean
    public RequestInterceptor flightServiceTokenInterceptor(ServiceTokenProvider provider) {
        return new ServiceTokenFeignInterceptor(provider, "flight-service");
    }
}
