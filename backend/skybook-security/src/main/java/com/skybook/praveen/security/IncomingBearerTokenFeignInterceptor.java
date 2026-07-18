package com.skybook.praveen.security;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Propagates the CALLER's exact incoming bearer token onto an outbound Feign
 * call (SECURITY_HARDENING_MODULE.md §3.3) - used by "as user" query clients so
 * the downstream service authenticates the real end user/admin. Reads the raw
 * {@code Authorization} header of the current request; a no-op when there is no
 * request context (e.g. a Kafka/scheduler thread), which must instead use a
 * service token.
 */
public class IncomingBearerTokenFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        if (template.headers().containsKey("Authorization")) {
            return; // an explicit token was already set - don't clobber it
        }
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && !authorization.isBlank()) {
            template.header("Authorization", authorization);
        }
    }

    private HttpServletRequest currentRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        return (attributes instanceof ServletRequestAttributes servletAttributes)
                ? servletAttributes.getRequest() : null;
    }
}
