package com.skybook.praveen.security;

import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * Attaches this service's own {@code ROLE_SERVICE} token, targeting a fixed
 * audience, onto an outbound Feign call (SECURITY_HARDENING_MODULE.md §3.3) -
 * used by "as service" command clients for internal writes the matrix denies to
 * a USER. The token is cached/refreshed by {@link ServiceTokenProvider}.
 *
 * A per-target interceptor: a service constructs one per downstream audience in
 * that client's Feign configuration (never a global, privilege-inferring bean).
 */
public class ServiceTokenFeignInterceptor implements RequestInterceptor {

    private final ServiceTokenProvider provider;
    private final String audience;

    public ServiceTokenFeignInterceptor(ServiceTokenProvider provider, String audience) {
        this.provider = provider;
        this.audience = audience;
    }

    @Override
    public void apply(RequestTemplate template) {
        template.header("Authorization", "Bearer " + provider.tokenFor(audience));
    }
}
