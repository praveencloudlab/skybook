package com.skybook.praveen.security;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;

/**
 * Feign client configuration that propagates the incoming USER/ADMIN bearer
 * token (SECURITY_HARDENING_MODULE.md §3.3). Referenced explicitly via
 * {@code @FeignClient(configuration = UserTokenFeignConfiguration.class)} on
 * "read/as-user" clients - NOT annotated {@code @Configuration} and NOT
 * component-scanned, so its interceptor never leaks onto other Feign clients
 * (review 4). Feign instantiates it per client context.
 */
public class UserTokenFeignConfiguration {

    @Bean
    public RequestInterceptor incomingBearerTokenFeignInterceptor() {
        return new IncomingBearerTokenFeignInterceptor();
    }
}
