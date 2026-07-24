package com.skybook.praveen.bookingservice.config;

import com.skybook.praveen.security.ServiceTokenFeignInterceptor;
import com.skybook.praveen.security.ServiceTokenProvider;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;

/**
 * Feign configuration for booking-service's inventory COMMAND client
 * (SECURITY_HARDENING_MODULE.md §3.3): attaches a {@code ROLE_SERVICE} token
 * scoped to the {@code inventory-service} audience. NOT {@code @Configuration}
 * and NOT component-scanned - referenced only via
 * {@code @FeignClient(configuration = ...)}, so the service-token interceptor
 * applies to this one client and never leaks onto the query client.
 */
public class InventoryCommandFeignConfiguration {

    @Bean
    public RequestInterceptor inventoryServiceTokenInterceptor(ServiceTokenProvider provider) {
        return new ServiceTokenFeignInterceptor(provider, "inventory-service");
    }
}
