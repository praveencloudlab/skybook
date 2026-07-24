package com.skybook.praveen.checkinservice.config;

import com.skybook.praveen.security.ServiceTokenFeignInterceptor;
import com.skybook.praveen.security.ServiceTokenProvider;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;

/**
 * Feign configuration for check-in's inventory client
 * (SECURITY_HARDENING_MODULE.md §3.3): attaches a {@code ROLE_SERVICE} token
 * scoped to the {@code inventory-service} audience. Check-in's reservation
 * lookups and seat-change reserve/cancel are internal service→service calls
 * (inventory requires ADMIN/SERVICE), not user-scoped. NOT {@code @Configuration}
 * and NOT component-scanned - referenced only via
 * {@code @FeignClient(configuration = ...)}.
 */
public class InventoryCommandFeignConfiguration {

    @Bean
    public RequestInterceptor inventoryServiceTokenInterceptor(ServiceTokenProvider provider) {
        return new ServiceTokenFeignInterceptor(provider, "inventory-service");
    }
}
