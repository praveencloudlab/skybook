package com.skybook.praveen.security;

import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two outbound-identity interceptors (SECURITY_HARDENING_MODULE.md §3.3):
 * a query client propagates the caller's exact token; a command client attaches
 * this service's own ROLE_SERVICE token for the target audience.
 */
class FeignInterceptorTest {

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private String header(RequestTemplate template) {
        Collection<String> values = template.headers().get("Authorization");
        return values == null || values.isEmpty() ? null : values.iterator().next();
    }

    @Test
    void incomingInterceptorPropagatesTheCallersToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer user-token-abc");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        RequestTemplate template = new RequestTemplate();
        new IncomingBearerTokenFeignInterceptor().apply(template);

        assertThat(header(template)).isEqualTo("Bearer user-token-abc");
    }

    @Test
    void incomingInterceptorIsANoOpWithoutARequestContext() {
        RequestTemplate template = new RequestTemplate();
        new IncomingBearerTokenFeignInterceptor().apply(template);

        assertThat(header(template)).isNull();
    }

    @Test
    void serviceInterceptorAttachesTheProvidersTokenForItsAudience() {
        // A stub provider returns a fixed token for the configured audience.
        ServiceTokenProvider provider = new ServiceTokenProvider(
                audience -> new ServiceTokenProvider.ServiceToken(
                        "svc-token-for-" + audience, Instant.now().plusSeconds(600)));

        RequestTemplate template = new RequestTemplate();
        new ServiceTokenFeignInterceptor(provider, "inventory-service").apply(template);

        assertThat(header(template)).isEqualTo("Bearer svc-token-for-inventory-service");
    }
}
